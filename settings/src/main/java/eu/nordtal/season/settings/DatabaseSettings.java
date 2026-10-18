package eu.nordtal.season.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.season.database.inbox.SettingsChange;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.spec.ManagedSpecReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;

/** Reads settings from the database.
 * A group takes the spec's defaults, then the stored values, then the environment.
 * A refused change keeps the last values.
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

    public static DatabaseSettings over(
            final SettingStore store, final String service, final Environment environment, final Logger logger) {
        return new DatabaseSettings(store, service, environment, logger);
    }

    /** Returns {@code group} of {@code service} as that service takes it at its start.
     * Nothing is published. No environment applies, and refused rows give the defaults.
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
     * Returns {@code group} as this process would read it with {@code change} saved over the stored rows.
     *
     * @throws SettingsException if the group's check refuses the result
     */
    public <T> T trial(final Group<T> group, final SettingsChange change) throws SettingsException {
        final String owner = group.network() ? SettingStore.NETWORK : service;
        final List<SettingStore.Value> stored = new ArrayList<>(store.overrides(List.of(owner)).stream()
                .filter(value -> value.group().equals(group.name()))
                .filter(value -> !change.resets().contains(value.path())
                        && !change.values().containsKey(value.path()))
                .toList());
        change.values().forEach((path, value) -> stored.add(new SettingStore.Value(owner, group.name(), path, value)));
        return compose(group, defaultsOf(group), stored, environment, ignored -> {});
    }

    /**
     * Calls {@code onChange} on the hub's thread whenever a stored value of this process changed.
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

    private static JsonObject defaultsOf(final Group<?> group) {
        final JsonObject defaults = SpecJson.defaults(group.spec());
        group.defaults().forEach((path, value) -> SpecJson.put(defaults, path, SpecJson.GSON.toJsonTree(value)));
        return defaults;
    }

    /**
     * Applies the stored rows to the defaults, then the environment, and checks the result.
     *
     * @param unknown receives each stored path the group has no setting for or keeps secret. Such a
     *     row is skipped.
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

        /** Takes the stored values, or the defaults if the check refuses them.
         * A stored value never stops a start.
         */
        private void start() throws SettingsException {
            try {
                values.set(compose(read()));
            } catch (final SettingsException refusedStored) {
                refuse(refusedStored);
                try {
                    values.set(compose(List.of()));
                } catch (final SettingsException refusedWithout) {
                    refusedStored.addSuppressed(refusedWithout);
                    throw refusedStored;
                }
            }
        }

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
