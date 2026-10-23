package eu.nordtal.season.steward.settings;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.season.common.Sha256;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.spec.schema.SchemaNode;
import eu.nordtal.season.spec.schema.SettingType;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Holds one group of settings as the form draws it. A value equal to the default removes its row. */
public final class SettingsDocument {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    private final SettingStore.Group group;
    private final JsonObject schema;
    private final JsonObject defaults;
    private final JsonObject values;
    private final Set<String> environment;
    private final String revision;
    private final Names names;

    private SettingsDocument(final SettingStore.Group group, final List<SettingStore.Value> stored, final Names names) {
        this.group = group;
        this.names = names;
        this.schema = JsonParser.parseString(group.schema()).getAsJsonObject();
        this.defaults = JsonParser.parseString(group.defaults()).getAsJsonObject();
        this.values = defaults.deepCopy();
        for (final SettingStore.Value value : stored) {
            put(values, value.path(), JsonParser.parseString(value.value()));
        }
        this.environment = new HashSet<>(group.environment());
        this.revision = revisionOf(stored);
    }

    static SettingsDocument of(
            final SettingStore.Group group, final List<SettingStore.Value> stored, final Names names) {
        return new SettingsDocument(group, stored, names);
    }

    static Location describe(final SettingStore.Group group) {
        return new Location(
                group.service(),
                group.name(),
                group.service() + "/" + group.name(),
                label(JsonParser.parseString(group.schema()).getAsJsonObject(), group.name()),
                group.live(),
                group.problem(),
                true,
                true);
    }

    /** Returns a fingerprint of the stored rows. A save sends it back, and a change in between is a conflict. */
    static String revisionOf(final List<SettingStore.Value> stored) {
        final StringBuilder text = new StringBuilder();
        stored.stream()
                .sorted(Comparator.comparing(SettingStore.Value::path))
                .forEach(value -> text.append(value.path())
                        .append('=')
                        .append(JsonParser.parseString(value.value()))
                        .append('\n'));
        return Sha256.hex(text.toString());
    }

    Document document(final @Nullable Reloading reload) {
        final Location location = describe(group);
        final List<Entry> entries = new ArrayList<>();
        collect(schema, "", entries);
        return new Document(
                location.service(),
                location.name(),
                location.path(),
                location.label(),
                location.live(),
                location.problem(),
                location.readable(),
                location.writable(),
                revision,
                entries,
                reload);
    }

    private void collect(final JsonObject node, final String prefix, final List<Entry> into) {
        for (final Map.Entry<String, JsonElement> child : children(node).entrySet()) {
            final String path = prefix.isEmpty() ? child.getKey() : prefix + "." + child.getKey();
            final JsonObject childNode = child.getValue().getAsJsonObject();
            final JsonElement value = at(values, path);
            into.add(entry(path, child.getKey(), childNode, value, value != null && !isBlank(value)));
            if ("MAP".equals(kindOf(childNode))) {
                collect(childNode, path, into);
            }
        }
    }

    private Entry entry(
            final String path,
            final String key,
            final JsonObject node,
            final @Nullable JsonElement value,
            final boolean filled) {
        final Shape kind = formKindOf(node);
        final boolean secret = flag(node, "secret");
        // A secret shows only whether it is set.
        final boolean shown = !secret;
        return new Entry(
                path,
                key,
                label(node, key),
                text(node, "explanation"),
                flag(node, "noExplanationNeeded"),
                kind,
                typeOf(node, kind),
                kind != Shape.MAP && !secret,
                secret,
                environment.contains(path),
                secret ? environment.contains(path) : filled,
                shown && kind == Shape.SCALAR ? (value == null || value.isJsonNull() ? "" : scalarText(value)) : null,
                shown && kind == Shape.LIST ? itemsOf(value) : null,
                shown && kind == Shape.SECTIONS ? templateOf(node) : null,
                shown && kind == Shape.SECTIONS ? sectionsOf(node, path, value) : null,
                choicesOf(node),
                objectAt(node, "protectedEntry", SchemaNode.ProtectedEntry.class),
                objectAt(node, "refers", Reference.class),
                objectAt(node, "appliesWhen", Condition.class));
    }

    /** Returns the choices of the node with the plugin name and icon of each value. */
    private @Nullable Choices choicesOf(final JsonObject node) {
        final SchemaNode.@Nullable Choices choices = objectAt(node, "choices", SchemaNode.Choices.class);
        if (choices == null) {
            return null;
        }
        final JsonObject declared = node.getAsJsonObject("choices");
        final String key = text(declared, "names");
        final Map<String, String> named = new LinkedHashMap<>();
        final Map<String, String> icons = new LinkedHashMap<>();
        final JsonArray items = declared.get("icons") instanceof final JsonArray array ? array : new JsonArray();
        for (int index = 0; index < choices.values().size(); index++) {
            final String value = choices.values().get(index);
            final String name = key.isEmpty()
                    ? null
                    : names.name(key + "." + value.toLowerCase(Locale.ROOT).replace('_', '-'));
            if (name != null) {
                named.put(value, name);
            }
            if (index < items.size()) {
                icons.put(value, items.get(index).getAsString());
            }
        }
        return new Choices(
                choices.values(), choices.strict(), key.isEmpty() ? null : named, items.isEmpty() ? null : icons);
    }

    private List<List<Entry>> sectionsOf(final JsonObject node, final String path, final @Nullable JsonElement value) {
        final List<List<Entry>> sections = new ArrayList<>();
        if (value instanceof final JsonArray elements) {
            for (int index = 0; index < elements.size(); index++) {
                sections.add(fieldsOf(node, path + "[" + index + "]", elements.get(index)));
            }
        }
        return sections;
    }

    private List<Entry> templateOf(final JsonObject node) {
        final JsonObject blank =
                node.get("defaults") instanceof final JsonObject defaulted ? defaulted : new JsonObject();
        final List<Entry> fields = new ArrayList<>();
        for (final Map.Entry<String, JsonElement> field : children(node).entrySet()) {
            final JsonElement value = blank.get(field.getKey());
            fields.add(entry(
                    field.getKey(),
                    field.getKey(),
                    field.getValue().getAsJsonObject(),
                    value,
                    value != null && !isBlank(value)));
        }
        return fields;
    }

    /** A field the stored section lacks shows the template's default, and is not {@code filled} until a save. */
    private List<Entry> fieldsOf(final JsonObject node, final String prefix, final JsonElement element) {
        final List<Entry> fields = new ArrayList<>();
        final JsonObject object = element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        final JsonObject blank =
                node.get("defaults") instanceof final JsonObject defaulted ? defaulted : new JsonObject();
        for (final Map.Entry<String, JsonElement> field : children(node).entrySet()) {
            final boolean stored = object.has(field.getKey());
            fields.add(entry(
                    prefix + "." + field.getKey(),
                    field.getKey(),
                    field.getValue().getAsJsonObject(),
                    stored ? object.get(field.getKey()) : blank.get(field.getKey()),
                    stored));
        }
        return fields;
    }

    /**
     * Returns the rows a save writes: JSON text per path, or {@code null} where the value equals the default.
     *
     * @throws RequestRefused for an unknown path, a secret or a value of the wrong type
     */
    Map<String, @Nullable String> rowsFor(final JsonObject changes) {
        final Map<String, @Nullable String> rows = new LinkedHashMap<>();
        for (final Map.Entry<String, JsonElement> change : changes.entrySet()) {
            final String path = change.getKey();
            final JsonObject node = nodeAt(path);
            if (node == null || "MAP".equals(kindOf(node))) {
                throw new RequestRefused(400, ANSWER.noSetting(group.name(), path));
            }
            if (node.has("secret") && node.get("secret").getAsBoolean()) {
                throw new RequestRefused(400, ANSWER.secretSetting(path));
            }
            final JsonElement value = SettingValues.convert(path, node, change.getValue());
            SettingValues.refuseRemovingTheProtected(path, node, value);
            final JsonElement fallback = at(defaults, path);
            rows.put(path, value.equals(fallback) ? null : value.toString());
        }
        return rows;
    }

    private @Nullable JsonObject nodeAt(final String path) {
        JsonObject node = schema;
        for (final String segment : path.split("\\.", -1)) {
            final JsonElement child = children(node).get(segment);
            if (child == null || !child.isJsonObject()) {
                return null;
            }
            node = child.getAsJsonObject();
        }
        return node;
    }

    private static JsonObject children(final JsonObject node) {
        final JsonElement children = node.get("children");
        if (children == null || !children.isJsonObject()) {
            return new JsonObject();
        }
        final JsonObject stored = children.getAsJsonObject();
        final List<String> order = new ArrayList<>();
        if (node.get("order") instanceof final JsonArray named) {
            named.forEach(key -> order.add(key.getAsString()));
        }
        final JsonObject ordered = new JsonObject();
        stored.keySet().stream()
                .sorted(Comparator.comparingInt(key -> order.contains(key) ? order.indexOf(key) : order.size()))
                .forEach(key -> ordered.add(key, stored.get(key)));
        return ordered;
    }

    private static String kindOf(final JsonObject node) {
        return text(node, "kind");
    }

    private static Shape formKindOf(final JsonObject node) {
        final Shape kind = Shape.valueOf(kindOf(node));
        return kind == Shape.LIST && !children(node).isEmpty() ? Shape.SECTIONS : kind;
    }

    private static SettingType typeOf(final JsonObject node, final Shape kind) {
        final JsonElement type = node.get("type");
        return type == null || type.isJsonNull() || kind == Shape.SECTIONS
                ? SettingType.STRING
                : SettingType.valueOf(type.getAsString());
    }

    private static boolean flag(final JsonObject node, final String field) {
        final JsonElement value = node.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }

    private static <T> @Nullable T objectAt(final JsonObject node, final String field, final Class<T> type) {
        final JsonElement value = node.get(field);
        return value != null && value.isJsonObject() ? Json.gson().fromJson(value, type) : null;
    }

    private static String label(final JsonObject node, final String fallback) {
        final String label = text(node, "label");
        return label.isEmpty() ? fallback : label;
    }

    private static String text(final JsonObject node, final String field) {
        final JsonElement value = node.get(field);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static boolean isBlank(final JsonElement value) {
        return value.isJsonNull()
                || (value.isJsonPrimitive() && value.getAsString().isBlank())
                || (value.isJsonArray() && value.getAsJsonArray().isEmpty());
    }

    private static String scalarText(final JsonElement value) {
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    private static List<String> itemsOf(final @Nullable JsonElement value) {
        final List<String> items = new ArrayList<>();
        if (value instanceof final JsonArray array) {
            array.forEach(item -> items.add(scalarText(item)));
        }
        return items;
    }

    /** Returns the value at a dotted path, or {@code null} when a segment is absent. */
    static @Nullable JsonElement at(final JsonObject tree, final String path) {
        JsonElement node = tree;
        for (final String segment : path.split("\\.", -1)) {
            if (!(node instanceof final JsonObject object) || !object.has(segment)) {
                return null;
            }
            node = object.get(segment);
        }
        return node;
    }

    private static void put(final JsonObject tree, final String path, final JsonElement value) {
        final String[] segments = path.split("\\.", -1);
        JsonObject object = tree;
        for (int i = 0; i < segments.length - 1; i++) {
            if (!(object.get(segments[i]) instanceof final JsonObject nested)) {
                final JsonObject made = new JsonObject();
                object.add(segments[i], made);
                object = made;
            } else {
                object = nested;
            }
        }
        object.add(segments[segments.length - 1], value);
    }

    public enum Shape {
        SCALAR,
        LIST,
        MAP,
        SECTIONS
    }

    public record Location(
            String service,
            String name,
            String path,
            String label,
            boolean live,
            @Nullable String problem,
            boolean readable,
            boolean writable) {}

    /** @param revision the fingerprint of the stored rows */
    public record Document(
            String service,
            String name,
            String path,
            String label,
            boolean live,
            @Nullable String problem,
            boolean readable,
            boolean writable,
            String revision,
            List<Entry> entries,
            @Nullable Reloading reload) {}

    /**
     * Holds one key of a group. A secret has {@code filled} but never its value.
     * A stored section's field is {@code filled} when the section holds it, and one it lacks carries its default.
     */
    public record Entry(
            String path,
            String key,
            String label,
            String explanation,
            boolean noExplanationNeeded,
            Shape kind,
            SettingType type,
            boolean editable,
            boolean secret,
            boolean environmentOverridden,
            boolean filled,
            @Nullable String value,
            @Nullable List<String> items,
            @Nullable List<Entry> template,
            @Nullable List<List<Entry>> sections,
            @Nullable Choices choices,
            SchemaNode.@Nullable ProtectedEntry protectedEntry,
            @Nullable Reference refers,
            @Nullable Condition appliesWhen) {}

    /** Holds the values a setting offers. {@code names} is null when the schema names no texts. */
    public record Choices(
            List<String> values,
            boolean strict,
            @Nullable Map<String, String> names,
            @Nullable Map<String, String> icons) {}

    public record Condition(String key, List<String> values) {}

    @FunctionalInterface
    public interface Names {

        @Nullable
        String name(String key);
    }

    public record Reference(
            Refers.To to,
            @Nullable String dependsOn,
            boolean optional,
            @Nullable List<String> except) {}
}
