package eu.nordtal.s2.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.jcore.config.spec.ManagedSpecReference;
import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.notify.Channel;
import eu.nordtal.s2.database.notify.SignalHub;
import eu.nordtal.s2.database.setting.SettingStore;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
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
    private final @Nullable LegacyFiles legacy;
    private final Set<String> loaded = ConcurrentHashMap.newKeySet();
    private volatile List<SettingStore.Value> seen = List.of();

    private DatabaseSettings(
            final SettingStore store,
            final String service,
            final Environment environment,
            final Logger logger,
            final @Nullable LegacyFiles legacy) {
        this.store = Objects.requireNonNull(store, "store");
        this.service = Objects.requireNonNull(service, "service");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.legacy = legacy;
    }

    /**
     * Returns the settings of {@code service} in {@code store}.
     *
     * @param service the module that loads them, such as {@code smp}, which names its groups in the database
     */
    public static DatabaseSettings over(
            final SettingStore store, final String service, final Environment environment, final Logger logger) {
        return new DatabaseSettings(store, service, environment, logger, null);
    }

    /**
     * Returns these settings importing each group's YAML file from {@code folder} as it loads.
     *
     * @param skipped {@code group/path} of every value never imported, whatever its file says
     */
    public DatabaseSettings importingFrom(final Path folder, final Set<String> skipped) {
        return new DatabaseSettings(store, service, environment, logger, new LegacyFiles(folder, skipped, logger));
    }

    @Override
    public <T> Setting<T> load(final Group<T> group) throws SettingsException {
        final String owner = group.network() ? SettingStore.NETWORK : service;
        final JsonObject defaults = SpecJson.defaults(group.spec());
        group.defaults().forEach((path, value) -> SpecJson.put(defaults, path, SpecJson.GSON.toJsonTree(value)));
        final List<String> held = environment.applyTo(group, SpecJson.read(defaults, group.spec()));
        try {
            store.publish(owner, group.name(), SpecJson.schema(group.spec()), defaults.toString(), held, group.live());
            if (legacy != null) {
                final Set<String> excluded = new HashSet<>(held);
                excluded.addAll(SpecJson.secrets(group.spec()));
                final List<String> imported =
                        store.importMissing(owner, group.name(), legacy.changed(group, excluded), Actor.HOST);
                if (!imported.isEmpty()) {
                    logger.info("{}.yml: imported {}", group.name(), imported);
                }
            }
        } catch (final RuntimeException unreachable) {
            throw new SettingsException(group.name() + ": not published: " + unreachable.getMessage(), unreachable);
        }
        loaded.add(group.name());
        final Stored<T> setting = new Stored<>(group, owner, defaults);
        setting.start();
        return setting;
    }

    /** Deletes the YAML files every loaded group was imported from, and the bootstrap's; call it once all loaded. */
    public void retireFiles() {
        if (legacy != null) {
            final Set<String> groups = new HashSet<>(loaded);
            groups.add("database");
            legacy.retire(groups);
        }
    }

    /**
     * Calls {@code onChange} on the hub's thread whenever a stored value of this process changed; before its start.
     *
     * A reconciliation finds nothing changed and calls nothing, so this costs one read a minute.
     */
    public void listen(final SignalHub hub, final Runnable onChange) {
        seen = store.overrides(services());
        hub.on(Channel.SETTINGS, "settings", () -> {
            final List<SettingStore.Value> now = store.overrides(services());
            if (!now.equals(seen)) {
                seen = now;
                onChange.run();
            }
        });
    }

    private List<String> services() {
        return List.of(service, SettingStore.NETWORK);
    }

    /** One group, as last taken. */
    private final class Stored<T> implements Setting<T> {

        private final Group<T> group;
        private final String owner;
        private final JsonObject defaults;
        private final Set<String> paths;
        private final Set<String> secrets;
        private final ManagedSpecReference<T> values;
        private volatile boolean refused;

        private Stored(final Group<T> group, final String owner, final JsonObject defaults) {
            this.group = group;
            this.owner = owner;
            this.defaults = defaults;
            this.paths = Set.copyOf(SpecJson.leaves(group.spec()));
            this.secrets = SpecJson.secrets(group.spec());
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
            final JsonObject tree = defaults.deepCopy();
            try {
                for (final SettingStore.Value value : stored) {
                    if (!paths.contains(value.path()) || secrets.contains(value.path())) {
                        logger.warn(
                                "{}/{} stores {}, which {} has no setting for: it is ignored",
                                owner,
                                group.name(),
                                value.path(),
                                service);
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

        private void refuse(final SettingsException why) {
            refused = true;
            logger.error("{}/{} keeps the values it runs with: {}", owner, group.name(), why.getMessage());
            store.problem(owner, group.name(), why.getMessage());
        }
    }
}
