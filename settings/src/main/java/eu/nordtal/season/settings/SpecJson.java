package eu.nordtal.season.settings;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.season.spec.SpecPaths;
import eu.nordtal.season.spec.SpecProperty;
import eu.nordtal.season.spec.Specs;
import eu.nordtal.season.spec.schema.SchemaNode;
import eu.nordtal.season.spec.schema.SchemaWriter;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** A spec's values as a JSON tree keyed like its YAML, and its leaves by dotted path. */
final class SpecJson {

    /** Spec's own Gson, so a value reads back exactly as a file would have given it. */
    static final Gson GSON = Specs.gsonBuilder().disableHtmlEscaping().create();

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
        if (node.kind() == eu.nordtal.season.spec.schema.SettingKind.MAP) {
            for (final Map.Entry<String, SchemaNode> child : node.children().entrySet()) {
                collectSecrets(child.getValue(), path.isEmpty() ? child.getKey() : path + "." + child.getKey(), into);
            }
        }
    }

    /**
     * Returns the spec's schema tree as JSON text, as Steward draws a group from it, with what each value names.
     *
     * Every node with children names their keys in {@code order}, since the database keeps no object's key order.
     */
    static String schema(final Class<?> spec) {
        final JsonObject tree = GSON.toJsonTree(SchemaWriter.build(spec)).getAsJsonObject();
        addReferences(spec, tree);
        addOrder(tree);
        return GSON.toJson(tree);
    }

    /** Puts the keys of each node's {@code children} on it as {@code order}, in the spec's order, at every depth. */
    private static void addOrder(final JsonObject node) {
        if (!(node.get("children") instanceof final JsonObject children) || children.isEmpty()) {
            return;
        }
        final JsonArray order = new JsonArray();
        for (final Map.Entry<String, JsonElement> child : children.entrySet()) {
            order.add(child.getKey());
            addOrder(child.getValue().getAsJsonObject());
        }
        node.add("order", order);
    }

    /**
     * Puts each {@link Refers}, {@link AppliesWhen} and {@link ChoiceNames} on its node, at every depth.
     * A list of specs also carries the {@code defaults} a new entry starts from.
     */
    private static void addReferences(final Class<?> spec, final JsonObject node) {
        if (!(node.get("children") instanceof final JsonObject children)) {
            return;
        }
        for (final SpecProperty property : Specs.from(spec).properties().values()) {
            if (property.isHandledByProxy() || !(children.get(property.key()) instanceof final JsonObject child)) {
                continue;
            }
            final Method getter = property.getter();
            final Refers refers = getter.getAnnotation(Refers.class);
            if (refers != null) {
                child.add("refers", referenceOf(refers));
            }
            final AppliesWhen applies = getter.getAnnotation(AppliesWhen.class);
            if (applies != null) {
                final JsonObject condition = new JsonObject();
                condition.addProperty("key", applies.key());
                condition.add("values", array(applies.values()));
                child.add("appliesWhen", condition);
            }
            final ChoiceNames names = getter.getAnnotation(ChoiceNames.class);
            if (names != null) {
                addNames(property.key(), names, child);
            }
            final Class<?> nested = nestedSpec(getter);
            if (nested != null) {
                if (!Specs.isConfigSpec(getter.getReturnType())) {
                    child.add("defaults", defaults(nested));
                }
                addReferences(nested, child);
            }
        }
    }

    private static JsonObject referenceOf(final Refers refers) {
        final JsonObject declared = new JsonObject();
        declared.addProperty("to", refers.value().name());
        if (!refers.dependsOn().isEmpty()) {
            declared.addProperty("dependsOn", refers.dependsOn());
        }
        declared.addProperty("optional", refers.optional());
        if (refers.except().length > 0) {
            declared.add("except", array(refers.except()));
        }
        return declared;
    }

    /** Puts the names' key and the icons on the node's choices, refusing a count of icons that matches no value. */
    private static void addNames(final String key, final ChoiceNames names, final JsonObject node) {
        if (!(node.get("choices") instanceof final JsonObject choices)
                || !(choices.get("values") instanceof final JsonArray values)) {
            throw new IllegalStateException(key + " names its choices but offers none");
        }
        if (names.icons().length > 0 && names.icons().length != values.size()) {
            throw new IllegalStateException(key + " has " + values.size() + " choices but " + names.icons().length
                    + " icons; it needs one for each or none");
        }
        choices.addProperty("names", names.value());
        if (names.icons().length > 0) {
            choices.add("icons", array(names.icons()));
        }
    }

    private static JsonArray array(final String[] values) {
        final JsonArray array = new JsonArray();
        for (final String value : values) {
            array.add(value);
        }
        return array;
    }

    /** Returns the spec a getter holds, alone or as the entries of a list, or {@code null} for a plain value. */
    private static @Nullable Class<?> nestedSpec(final Method getter) {
        if (Specs.isConfigSpec(getter.getReturnType())) {
            return getter.getReturnType();
        }
        if (getter.getGenericReturnType() instanceof final ParameterizedType list
                && Collection.class.isAssignableFrom(getter.getReturnType())
                && list.getActualTypeArguments()[0] instanceof final Class<?> element
                && Specs.isConfigSpec(element)) {
            return element;
        }
        return null;
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
