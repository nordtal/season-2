package eu.nordtal.s2.settings;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The variables a process's environment overrides its own settings by, for bootstrap and secrets.
 *
 * @param prefix    such as {@code NORDTAL_SMP}
 * @param main      the group whose values take the bare prefix
 * @param variables what a variable is set to, {@code null} when it is not
 */
public record Environment(String prefix, String main, Function<String, @Nullable String> variables) {

    public Environment {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(main, "main");
        Objects.requireNonNull(variables, "variables");
    }

    /** Returns the process's own environment under {@code prefix}, whose main group is {@code config}. */
    public static Environment of(final String prefix) {
        return new Environment(prefix, "config", System::getenv);
    }

    /** Returns this environment with {@code group} as the main one. */
    public Environment withMain(final String group) {
        return new Environment(prefix, group, variables);
    }

    /** Returns this environment reading its variables from {@code source}, for a test. */
    public Environment reading(final Function<String, @Nullable String> source) {
        return new Environment(prefix, main, source);
    }

    /** Returns the variable prefix of a group: the bare prefix for the main one, {@code <prefix>_<GROUP>} otherwise. */
    public String prefixOf(final String group) {
        return group.equals(main)
                ? prefix
                : prefix + "_" + group.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    /** Overrides {@code values} wherever a variable is set and returns the paths; a network-wide group takes none. */
    List<String> applyTo(final Group<?> group, final Object values) {
        if (group.network()) {
            return List.of();
        }
        return EnvOverlay.forSpec(group.spec(), prefixOf(group.name()), variables, SpecJson.GSON)
                .applyTo(values);
    }
}
