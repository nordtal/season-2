package eu.nordtal.s2.steward.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
    static Map<String, Object> describe(final SettingStore.Group group) {
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("service", group.service());
        answer.put("name", group.name());
        answer.put("path", group.service() + "/" + group.name());
        answer.put("label", label(JsonParser.parseString(group.schema()).getAsJsonObject(), group.name()));
        answer.put("live", group.live());
        answer.put("problem", group.problem());
        answer.put("readable", true);
        answer.put("writable", true);
        return answer;
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

    /** Returns the document as the page reads it. */
    Map<String, Object> toJson() {
        final Map<String, Object> answer = describe(group);
        answer.put("revision", revision);
        answer.put("restartRequired", !group.live());
        final List<Map<String, Object>> entries = new ArrayList<>();
        collect(schema, "", entries);
        answer.put("entries", entries);
        return answer;
    }

    private void collect(final JsonObject node, final String prefix, final List<Map<String, Object>> into) {
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
    private Map<String, Object> entry(
            final String path, final String key, final JsonObject node, final @Nullable JsonElement value) {
        final String kind = formKindOf(node);
        final boolean secret = node.has("secret") && node.get("secret").getAsBoolean();
        final Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("path", path);
        entry.put("key", key);
        entry.put("label", label(node, key));
        entry.put("explanation", text(node, "explanation"));
        entry.put(
                "noExplanationNeeded",
                node.has("noExplanationNeeded")
                        && node.get("noExplanationNeeded").getAsBoolean());
        entry.put("kind", kind);
        entry.put(
                "type",
                node.has("type") && !"SECTIONS".equals(kind) ? node.get("type").getAsString() : "STRING");
        // A secret is the environment's, so the form shows only whether it is there.
        entry.put("editable", !"MAP".equals(kind) && !secret);
        entry.put("secret", secret);
        entry.put("environmentOverridden", environment.contains(path));
        entry.put("filled", secret ? environment.contains(path) : value != null && !isBlank(value));
        if (!secret) {
            fill(entry, kind, node, path, value);
        }
        if (node.has("choices") && node.get("choices").isJsonObject()) {
            entry.put("choices", node.get("choices"));
        }
        if (node.has("protectedEntry") && node.get("protectedEntry").isJsonObject()) {
            entry.put("protectedEntry", node.get("protectedEntry"));
        }
        return entry;
    }

    private void fill(
            final Map<String, Object> entry,
            final String kind,
            final JsonObject node,
            final String path,
            final @Nullable JsonElement value) {
        switch (kind) {
            case "SCALAR" -> entry.put("value", value == null || value.isJsonNull() ? "" : scalarText(value));
            case "LIST" -> entry.put("items", itemsOf(value));
            case "SECTIONS" -> {
                entry.put("template", templateOf(node));
                final List<List<Map<String, Object>>> sections = new ArrayList<>();
                if (value instanceof final JsonArray elements) {
                    for (int index = 0; index < elements.size(); index++) {
                        sections.add(fieldsOf(node, path + "[" + index + "]", elements.get(index)));
                    }
                }
                entry.put("sections", sections);
            }
            default -> {
                // A section heading holds nothing of its own.
            }
        }
    }

    private List<Map<String, Object>> templateOf(final JsonObject node) {
        final List<Map<String, Object>> fields = new ArrayList<>();
        for (final Map.Entry<String, JsonElement> field : children(node).entrySet()) {
            fields.add(entry(field.getKey(), field.getKey(), field.getValue().getAsJsonObject(), null));
        }
        return fields;
    }

    private List<Map<String, Object>> fieldsOf(final JsonObject node, final String prefix, final JsonElement element) {
        final List<Map<String, Object>> fields = new ArrayList<>();
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
    private static String formKindOf(final JsonObject node) {
        final String kind = kindOf(node);
        return "LIST".equals(kind) && !children(node).isEmpty() ? "SECTIONS" : kind;
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
}
