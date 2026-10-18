package eu.nordtal.season.settings;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Reads the environment variables that override the settings of a process.
 *
 * @param prefix such as {@code NORDTAL_SMP}
 * @param main the group whose variables take the bare prefix
 */
public record Environment(String prefix, String main, Function<String, @Nullable String> variables) {

    public Environment {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(main, "main");
        Objects.requireNonNull(variables, "variables");
    }

    public static Environment of(final String prefix) {
        return new Environment(prefix, "config", System::getenv);
    }

    public Environment withMain(final String group) {
        return new Environment(prefix, group, variables);
    }

    public Environment reading(final Function<String, @Nullable String> source) {
        return new Environment(prefix, main, source);
    }

    public String prefixOf(final String group) {
        return group.equals(main)
                ? prefix
                : prefix + "_" + group.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    /** Overrides {@code values} wherever a variable is set. A network-wide group takes none. */
    List<String> applyTo(final Group<?> group, final Object values) {
        if (group.network()) {
            return List.of();
        }
        return EnvOverlay.forSpec(group.spec(), prefixOf(group.name()), variables, SpecJson.GSON)
                .applyTo(values);
    }
}
