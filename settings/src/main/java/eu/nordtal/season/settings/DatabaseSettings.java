package eu.nordtal.season.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.spec.ManagedSpecReference;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;

/**
 * Settings in the database: the spec's defaults, then what an admin changed, then the environment.
 *
 * Every group is published with its schema at load, so Steward draws it; a refused change keeps the last values.
 */
public final class DatabaseSettings implements Settings {

    private final SettingStore store;
    private final String service;
    private final Environment environment;
    private final Logger logger;

    private DatabaseSettings(
            final SettingStore store, final String service, final Environment environment, final Logger logger) {
        this.store = Objects.requireNonNull(store, "store");
        this.service = Objects.requireNonNull(service, "service");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Returns the settings of {@code service} in {@code store}.
     *
     * @param service the module that loads them, such as {@code smp}, which names its groups in the database
     */
    public static DatabaseSettings over(
            final SettingStore store, final String service, final Environment environment, final Logger logger) {
        return new DatabaseSettings(store, service, environment, logger);
    }

    /**
     * Returns {@code group} of {@code service} as that service takes it at its start, without publishing anything.
     *
     * For a reader that shows another process's values: no environment applies, and refused rows give the defaults.
     */
    public static <T> T current(final SettingStore store, final String service, final Group<T> group)
            throws SettingsException {
        final String owner = group.network() ? SettingStore.NETWORK : service;
        final Environment none = new Environment("NONE", group.name(), variable -> null);
        final List<SettingStore.Value> stored = store.overrides(List.of(owner)).stream()
                .filter(value -> value.group().equals(group.name()))
                .toList();
        try {
            return compose(group, defaultsOf(group), stored, none, ignored -> {});
        } catch (final SettingsException refused) {
            return compose(group, defaultsOf(group), List.of(), none, ignored -> {});
        }
    }

    @Override
    public <T> Setting<T> load(final Group<T> group) throws SettingsException {
        final String owner = group.network() ? SettingStore.NETWORK : service;
        final JsonObject defaults = defaultsOf(group);
        final List<String> held = environment.applyTo(group, SpecJson.read(defaults, group.spec()));
        try {
            store.publish(owner, group.name(), SpecJson.schema(group.spec()), defaults.toString(), held, group.live());
        } catch (final RuntimeException unreachable) {
            throw new SettingsException(group.name() + ": not published: " + unreachable.getMessage(), unreachable);
        }
        final Stored<T> setting = new Stored<>(group, owner, defaults);
        setting.start();
        return setting;
    }

    /**
     * Calls {@code onChange} on the hub's thread whenever a stored value of this process changed; before its start.
     *
     * A reconciliation finds nothing changed and calls nothing, so this costs one read a minute.
     */
    public void listen(final SignalHub hub, final Runnable onChange) {
        hub.watch(
                Channel.SETTINGS,
                "settings",
                store.overrides(services()),
                () -> store.overrides(services()),
                changed -> onChange.run());
    }

    private List<String> services() {
        return List.of(service, SettingStore.NETWORK);
    }

    /** The spec's defaults under the group's own. */
    private static JsonObject defaultsOf(final Group<?> group) {
        final JsonObject defaults = SpecJson.defaults(group.spec());
        group.defaults().forEach((path, value) -> SpecJson.put(defaults, path, SpecJson.GSON.toJsonTree(value)));
        return defaults;
    }

    /**
     * Lays the stored rows over the defaults, then the environment, and checks the result.
     *
     * @param unknown told each stored path the group has no setting for, or keeps secret, which is skipped
     */
    private static <T> T compose(
            final Group<T> group,
            final JsonObject defaults,
            final List<SettingStore.Value> stored,
            final Environment environment,
            final Consumer<String> unknown)
            throws SettingsException {
        final Set<String> paths = Set.copyOf(SpecJson.leaves(group.spec()));
        final Set<String> secrets = SpecJson.secrets(group.spec());
        final JsonObject tree = defaults.deepCopy();
        try {
            for (final SettingStore.Value value : stored) {
                if (!paths.contains(value.path()) || secrets.contains(value.path())) {
                    unknown.accept(value.path());
                    continue;
                }
                final JsonElement held = JsonParser.parseString(value.value());
                if (held.isJsonNull()) {
                    throw new IllegalArgumentException(value.path() + " is stored without a value");
                }
                SpecJson.put(tree, value.path(), held);
            }
            final T taken = SpecJson.read(tree, group.spec());
            environment.applyTo(group, taken);
            group.check().check(taken);
            return taken;
        } catch (final RuntimeException wrong) {
            throw new SettingsException(group.name() + ": " + wrong.getMessage(), wrong);
        }
    }

    /** One group, as last taken. */
    private final class Stored<T> implements Setting<T> {

        private final Group<T> group;
        private final String owner;
        private final JsonObject defaults;
        private final ManagedSpecReference<T> values;
        private volatile boolean refused;

        private Stored(final Group<T> group, final String owner, final JsonObject defaults) {
            this.group = group;
            this.owner = owner;
            this.defaults = defaults;
            this.values = new ManagedSpecReference<>(group.spec(), this::reloadFromSpec, Stored::cannotSave);
        }

        /** Takes the stored values, or the defaults when they are refused: a stored value never stops a start. */
        private void start() throws SettingsException {
            try {
                values.set(compose(read()));
            } catch (final SettingsException refusedStored) {
                refuse(refusedStored);
                try {
                    values.set(compose(List.of()));
                } catch (final SettingsException refusedWithout) {
                    // The stored value is what to correct, so it is what the start names.
                    refusedStored.addSuppressed(refusedWithout);
                    throw refusedStored;
                }
            }
        }

        /** Returns the one instance this setting hands out, which reads through to the values of the last reload. */
        @Override
        public T get() {
            return values.get();
        }

        private void reloadFromSpec() {
            try {
                reload();
            } catch (final SettingsException refusedStored) {
                throw new IllegalStateException(refusedStored.getMessage(), refusedStored);
            }
        }

        private static void cannotSave() {
            throw new UnsupportedOperationException("settings are changed in Steward, never saved by a process");
        }

        @Override
        public void reload() throws SettingsException {
            if (!group.live()) {
                throw new IllegalStateException(group.name() + " applies at the next start, so nothing reloads it");
            }
            try {
                values.set(compose(read()));
            } catch (final SettingsException refusedStored) {
                refuse(refusedStored);
                throw refusedStored;
            }
            if (refused) {
                refused = false;
                store.problem(owner, group.name(), null);
            }
        }

        private List<SettingStore.Value> read() {
            return store.overrides(List.of(owner)).stream()
                    .filter(value -> value.group().equals(group.name()))
                    .toList();
        }

        private T compose(final List<SettingStore.Value> stored) throws SettingsException {
            return DatabaseSettings.compose(
                    group,
                    defaults,
                    stored,
                    environment,
                    path -> logger.warn(
                            "{}/{} stores {}, which {} has no setting for: it is ignored",
                            owner,
                            group.name(),
                            path,
                            service));
        }

        private void refuse(final SettingsException why) {
            refused = true;
            logger.error("{}/{} keeps the values it runs with: {}", owner, group.name(), why.getMessage());
            store.problem(owner, group.name(), why.getMessage());
        }
    }
}
