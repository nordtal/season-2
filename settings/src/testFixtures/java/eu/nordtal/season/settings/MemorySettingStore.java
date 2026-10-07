package eu.nordtal.season.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.setting.SettingStore;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

/**
 * A settings store in memory, for a test that loads settings the way its process does.
 *
 * A published schema and its defaults come back with their object keys in jsonb's order, as the database returns them.
 */
public final class MemorySettingStore implements SettingStore {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Map<String, Group> groups = new TreeMap<>();
    private final Map<String, Value> values = new TreeMap<>();
    private final Map<String, Actor> actors = new TreeMap<>();

    /** Returns the settings of {@code service} in this store, with an environment that sets nothing. */
    public DatabaseSettings settings(final String service) {
        return settings(service, Environment.of("NORDTAL_TEST").reading(variable -> null));
    }

    /** Returns the settings of {@code service} in this store, with {@code environment}. */
    public DatabaseSettings settings(final String service, final Environment environment) {
        return DatabaseSettings.over(this, service, environment, LoggerFactory.getLogger(MemorySettingStore.class));
    }

    /** Stores {@code value} at a path as an admin would, written as JSON; a string that is JSON goes in as it is. */
    public synchronized MemorySettingStore set(
            final String service, final String group, final String path, final Object value) {
        final String json = value instanceof final String text && looksLikeJson(text) ? text : GSON.toJson(value);
        values.put(key(service, group, path), new Value(service, group, path, json));
        actors.put(key(service, group, path), Actor.STEWARD);
        return this;
    }

    /**
     * Returns {@code group} of {@code service} taken over {@code values}, stored as an admin would store them.
     *
     * @throws SettingsException with the reason the check refused them, which a process would only record and start on
     */
    public <T> T checked(
            final String service, final eu.nordtal.season.settings.Group<T> group, final Map<String, ?> values)
            throws SettingsException {
        final String owner = group.network() ? NETWORK : service;
        values.forEach((path, value) -> set(owner, group.name(), path, value));
        final T taken = settings(service).load(group).get();
        final @Nullable String problem =
                group(owner, group.name()).map(Group::problem).orElse(null);
        if (problem != null) {
            throw new SettingsException(problem);
        }
        return taken;
    }

    /** Returns who stored the value at a path, or {@code null} when nothing is stored there. */
    public synchronized @Nullable Actor actorAt(final String service, final String group, final String path) {
        return actors.get(key(service, group, path));
    }

    @Override
    public synchronized void publish(
            final String service,
            final String name,
            final String schema,
            final String defaults,
            final List<String> environment,
            final boolean live) {
        groups.put(
                service + "/" + name,
                new Group(service, name, asJsonb(schema), asJsonb(defaults), environment, live, null, Instant.EPOCH));
    }

    @Override
    public synchronized void problem(final String service, final String name, final @Nullable String problem) {
        final Group group = groups.get(service + "/" + name);
        if (group != null) {
            groups.put(
                    service + "/" + name,
                    new Group(
                            service,
                            name,
                            group.schema(),
                            group.defaults(),
                            group.environment(),
                            group.live(),
                            problem,
                            group.published()));
        }
    }

    @Override
    public synchronized List<Value> overrides(final Collection<String> services) {
        return values.values().stream()
                .filter(value -> services.contains(value.service()))
                .toList();
    }

    @Override
    public synchronized List<Group> groups() {
        return List.copyOf(groups.values());
    }

    @Override
    public synchronized Optional<Group> group(final String service, final String name) {
        return Optional.ofNullable(groups.get(service + "/" + name));
    }

    @Override
    public synchronized boolean change(
            final String service,
            final String name,
            final Map<String, @Nullable String> changes,
            final Actor actor,
            final Predicate<List<Value>> current) {
        final List<Value> mine = values.values().stream()
                .filter(value ->
                        value.service().equals(service) && value.group().equals(name))
                .toList();
        if (!current.test(mine)) {
            return false;
        }
        changes.forEach((path, value) -> {
            if (value == null) {
                values.remove(key(service, name, path));
                actors.remove(key(service, name, path));
            } else {
                values.put(key(service, name, path), new Value(service, name, path, value));
                actors.put(key(service, name, path), actor);
            }
        });
        return true;
    }

    private static String key(final String service, final String group, final String path) {
        return service + "/" + group + "/" + path;
    }

    /** Returns {@code json} with every object's keys as jsonb keeps them: shorter keys first, then by their bytes. */
    private static String asJsonb(final String json) {
        return GSON.toJson(jsonbOrdered(JsonParser.parseString(json)));
    }

    private static JsonElement jsonbOrdered(final JsonElement element) {
        if (element instanceof final JsonArray array) {
            final JsonArray ordered = new JsonArray();
            array.forEach(item -> ordered.add(jsonbOrdered(item)));
            return ordered;
        }
        if (element instanceof final JsonObject object) {
            final JsonObject ordered = new JsonObject();
            object.keySet().stream()
                    .sorted(Comparator.comparingInt((String key) -> key.getBytes(StandardCharsets.UTF_8).length)
                            .thenComparing(key -> key.getBytes(StandardCharsets.UTF_8), Arrays::compareUnsigned))
                    .forEach(key -> ordered.add(key, jsonbOrdered(object.get(key))));
            return ordered;
        }
        return element;
    }

    private static boolean looksLikeJson(final String text) {
        try {
            final JsonElement parsed = JsonParser.parseString(text);
            return parsed.isJsonArray() || parsed.isJsonObject();
        } catch (final RuntimeException notJson) {
            return false;
        }
    }
}
