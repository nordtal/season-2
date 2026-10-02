package eu.nordtal.s2.steward.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.jcore.config.schema.SchemaNode;
import eu.nordtal.jcore.config.schema.SettingType;
import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.database.setting.SettingStore;
import io.javalin.http.BadRequestResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One group of settings as the form draws it: its published schema, its defaults and the rows an admin changed.
 *
 * A changed value equal to the default removes its row, so a row is always a difference from the process's own value.
 */
final class SettingsDocument {

    private final SettingStore.Group group;
    private final JsonObject schema;
    private final JsonObject defaults;
    private final JsonObject values;
    private final Set<String> environment;
    private final String revision;

    private SettingsDocument(final SettingStore.Group group, final List<SettingStore.Value> stored) {
        this.group = group;
        this.schema = JsonParser.parseString(group.schema()).getAsJsonObject();
        this.defaults = JsonParser.parseString(group.defaults()).getAsJsonObject();
        this.values = defaults.deepCopy();
        for (final SettingStore.Value value : stored) {
            put(values, value.path(), JsonParser.parseString(value.value()));
        }
        this.environment = new HashSet<>(group.environment());
        this.revision = revisionOf(stored);
    }

    /** Returns the document of {@code group} over its stored rows. */
    static SettingsDocument of(final SettingStore.Group group, final List<SettingStore.Value> stored) {
        return new SettingsDocument(group, stored);
    }

    /** Returns what the listing says of one group. */
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

    /** Returns a fingerprint of the stored rows, which a save sends back so a change in between is a conflict. */
    static String revisionOf(final List<SettingStore.Value> stored) {
        final StringBuilder text = new StringBuilder();
        stored.stream()
                .sorted(Comparator.comparing(SettingStore.Value::path))
                .forEach(value -> text.append(value.path())
                        .append('=')
                        .append(JsonParser.parseString(value.value()))
                        .append('\n'));
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("every JVM has SHA-256", impossible);
        }
    }

    /** Returns the document as the page reads it, with {@code reload} after a save and absent otherwise. */
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
                !group.live(),
                entries,
                reload);
    }

    private void collect(final JsonObject node, final String prefix, final List<Entry> into) {
        for (final Map.Entry<String, JsonElement> child : children(node).entrySet()) {
            final String path = prefix.isEmpty() ? child.getKey() : prefix + "." + child.getKey();
            final JsonObject childNode = child.getValue().getAsJsonObject();
            into.add(entry(path, child.getKey(), childNode, at(values, path)));
            if ("MAP".equals(kindOf(childNode))) {
                collect(childNode, path, into);
            }
        }
    }

    /** Returns one entry; {@code value} is what it holds now, absent for a template field. */
    private Entry entry(final String path, final String key, final JsonObject node, final @Nullable JsonElement value) {
        final Shape kind = formKindOf(node);
        final boolean secret = flag(node, "secret");
        // A secret is the environment's, so the form shows only whether it is there.
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
                secret ? environment.contains(path) : value != null && !isBlank(value),
                shown && kind == Shape.SCALAR ? (value == null || value.isJsonNull() ? "" : scalarText(value)) : null,
                shown && kind == Shape.LIST ? itemsOf(value) : null,
                shown && kind == Shape.SECTIONS ? templateOf(node) : null,
                shown && kind == Shape.SECTIONS ? sectionsOf(node, path, value) : null,
                objectAt(node, "choices", SchemaNode.Choices.class),
                objectAt(node, "protectedEntry", SchemaNode.ProtectedEntry.class));
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
        final List<Entry> fields = new ArrayList<>();
        for (final Map.Entry<String, JsonElement> field : children(node).entrySet()) {
            fields.add(entry(field.getKey(), field.getKey(), field.getValue().getAsJsonObject(), null));
        }
        return fields;
    }

    private List<Entry> fieldsOf(final JsonObject node, final String prefix, final JsonElement element) {
        final List<Entry> fields = new ArrayList<>();
        final JsonObject object = element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        for (final Map.Entry<String, JsonElement> field : children(node).entrySet()) {
            fields.add(entry(
                    prefix + "." + field.getKey(),
                    field.getKey(),
                    field.getValue().getAsJsonObject(),
                    object.get(field.getKey())));
        }
        return fields;
    }

    /**
     * Returns the rows a save writes: JSON text per path, or {@code null} where the value is back at the default.
     *
     * @throws BadRequestResponse for a path the group has no setting for, a secret, or a value of the wrong type
     */
    Map<String, @Nullable String> rowsFor(final JsonObject changes) {
        final Map<String, @Nullable String> rows = new LinkedHashMap<>();
        for (final Map.Entry<String, JsonElement> change : changes.entrySet()) {
            final String path = change.getKey();
            final JsonObject node = nodeAt(path);
            if (node == null || "MAP".equals(kindOf(node))) {
                throw new BadRequestResponse(group.name() + " has no setting " + path);
            }
            if (node.has("secret") && node.get("secret").getAsBoolean()) {
                throw new BadRequestResponse(path + " is a secret, which only the environment holds");
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
        return children != null && children.isJsonObject() ? children.getAsJsonObject() : new JsonObject();
    }

    private static String kindOf(final JsonObject node) {
        return text(node, "kind");
    }

    /** The form's kind: a list whose elements are sections is drawn as cards. */
    private static Shape formKindOf(final JsonObject node) {
        final Shape kind = Shape.valueOf(kindOf(node));
        return kind == Shape.LIST && !children(node).isEmpty() ? Shape.SECTIONS : kind;
    }

    /** A list of sections names no type of its own, and a node without one holds text. */
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

    /** How the form draws a setting: jcore's kinds, with a list of sections apart since it is drawn as cards. */
    public enum Shape {
        SCALAR,
        LIST,
        MAP,
        SECTIONS
    }

    /**
     * One group of settings a process published, as the listing names it.
     *
     * @param problem why the process refused the stored values and runs on its defaults; absent while it took them
     */
    public record Location(
            String service,
            String name,
            String path,
            String label,
            boolean live,
            @Nullable String problem,
            boolean readable,
            boolean writable) {}

    /**
     * One group as the form draws it.
     *
     * @param revision the fingerprint of the stored rows, which the save sends back so a write in between is a 409
     * @param reload what became of asking the process to take a save, on a save's answer alone
     */
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
            boolean restartRequired,
            List<Entry> entries,
            @Nullable Reloading reload) {}

    /**
     * One key of a group, as the form draws it; a secret carries {@code filled} alone, never its value.
     *
     * @param explanation the schema's short text, empty where no schema entry covers the key
     * @param editable false for a nested section, which has no value, and for a secret
     * @param environmentOverridden an environment variable overrides the key, so a saved value waits until it is gone
     * @param value a scalar's text, with a block scalar's newlines
     * @param items the entries of a list
     * @param template one section's fields in display order, the blank one "Add" starts from
     * @param sections one field list per section the list holds, in order
     * @param protectedEntry the one section a save may never remove
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
            SchemaNode.@Nullable Choices choices,
            SchemaNode.@Nullable ProtectedEntry protectedEntry) {}
}
