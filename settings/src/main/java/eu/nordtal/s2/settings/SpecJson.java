package eu.nordtal.s2.settings;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.internal.SpecPaths;
import eu.nordtal.jcore.config.schema.SchemaNode;
import eu.nordtal.jcore.config.schema.SchemaWriter;
import eu.nordtal.jcore.config.spec.Specs;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** A spec's values as a JSON tree keyed like its YAML, and its leaves by dotted path. */
final class SpecJson {

    /** jcore's own Gson for specs, so a value reads back exactly as a file would have given it. */
    static final Gson GSON = ConfigLoader.gsonBuilder().disableHtmlEscaping().create();

    private SpecJson() {}

    /** Returns the spec's own defaults as a tree. */
    static JsonObject defaults(final Class<?> spec) {
        return tree(Specs.createDefault(spec), spec);
    }

    /** Returns {@code values} of {@code spec} as a tree. */
    static JsonObject tree(final Object values, final Class<?> spec) {
        return GSON.toJsonTree(values, spec).getAsJsonObject();
    }

    /** Returns the tree as a spec instance; a key the spec does not declare is skipped. */
    static <T> T read(final JsonObject tree, final Class<T> spec) {
        return GSON.fromJson(tree, spec);
    }

    /** Returns the dotted path of every scalar and list of the spec, nested specs descended into. */
    static List<String> leaves(final Class<?> spec) {
        final List<String> paths = new ArrayList<>();
        for (final SpecPaths.Leaf leaf : SpecPaths.leaves(spec)) {
            paths.add(leaf.path());
        }
        return paths;
    }

    /** Returns the paths of the spec's leaves marked secret, which are never stored. */
    static Set<String> secrets(final Class<?> spec) {
        final Set<String> secret = new LinkedHashSet<>();
        collectSecrets(SchemaWriter.build(spec), "", secret);
        return secret;
    }

    private static void collectSecrets(final SchemaNode node, final String path, final Set<String> into) {
        if (node.secret()) {
            into.add(path);
        }
        if (node.kind() == eu.nordtal.jcore.config.schema.SettingKind.MAP) {
            for (final Map.Entry<String, SchemaNode> child : node.children().entrySet()) {
                collectSecrets(child.getValue(), path.isEmpty() ? child.getKey() : path + "." + child.getKey(), into);
            }
        }
    }

    /** Returns the spec's schema tree as JSON text, as Steward draws a group from it. */
    static String schema(final Class<?> spec) {
        return GSON.toJson(SchemaWriter.build(spec));
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

    /** Puts {@code value} at a dotted path, making every missing object on the way. */
    static void put(final JsonObject tree, final String path, final JsonElement value) {
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
