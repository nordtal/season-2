package eu.nordtal.s2.settings;

import com.google.gson.JsonObject;
import java.util.Objects;

/**
 * Settings from the spec's defaults and the environment alone, for what a process needs before its database.
 *
 * The connection to the database is the one group read this way, since the stored settings sit behind it.
 */
public final class EnvironmentSettings implements Settings {

    private final Environment environment;

    private EnvironmentSettings(final Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    /** Returns the settings {@code environment} holds. */
    public static EnvironmentSettings of(final Environment environment) {
        return new EnvironmentSettings(environment);
    }

    @Override
    public <T> Setting<T> load(final Group<T> group) throws SettingsException {
        final T values;
        try {
            final JsonObject defaults = SpecJson.defaults(group.spec());
            group.defaults().forEach((path, value) -> SpecJson.put(defaults, path, SpecJson.GSON.toJsonTree(value)));
            values = SpecJson.read(defaults, group.spec());
            environment.applyTo(group, values);
            group.check().check(values);
        } catch (final RuntimeException refused) {
            throw new SettingsException(
                    group.name() + " from " + environment.prefixOf(group.name()) + "_*: " + refused.getMessage(),
                    refused);
        }
        return new Fixed<>(values);
    }

    /** A group read once; the environment of a running process does not change. */
    private record Fixed<T>(T get) implements Setting<T> {

        @Override
        public void reload() {
            throw new IllegalStateException("a group from the environment is read once, at start");
        }
    }
}
