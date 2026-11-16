package eu.nordtal.season.settings;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import eu.nordtal.season.database.setting.SettingRule;
import java.util.Map;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/** Checks the worlds of a group's {@link Position} fields against the worlds a server has. */
public final class Positions {

    private Positions() {}

    /**
     * Refuses the first position of {@code values} whose world {@code loaded} does not hold.
     * A blank world is left to {@link eu.nordtal.season.settings.rule.Required}.
     *
     * @throws SettingRefused naming the world field, as the list and the field for an entry of a list
     */
    public static <T> void checkWorlds(final Class<T> spec, final T values, final Predicate<String> loaded) {
        walk(SpecJson.schemaTree(spec), SpecJson.tree(values, spec), "", loaded);
    }

    private static void walk(
            final JsonObject node,
            final @Nullable JsonElement value,
            final String path,
            final Predicate<String> loaded) {
        if (!(node.get("children") instanceof final JsonObject children)) {
            return;
        }
        for (final Map.Entry<String, JsonElement> child : children.entrySet()) {
            final JsonObject childNode = child.getValue().getAsJsonObject();
            final String at = path.isEmpty() ? child.getKey() : path + "." + child.getKey();
            final JsonElement held = value instanceof final JsonObject object ? object.get(child.getKey()) : null;
            if (childNode.has("position")) {
                if (held instanceof final JsonArray entries) {
                    entries.forEach(entry -> checkOne(entry, at, loaded));
                } else {
                    checkOne(held, at, loaded);
                }
            } else if (!(held instanceof JsonArray)) {
                walk(childNode, held, at, loaded);
            }
        }
    }

    private static void checkOne(final @Nullable JsonElement place, final String at, final Predicate<String> loaded) {
        if (!(place instanceof final JsonObject object)
                || !(object.get("world") instanceof final JsonPrimitive world)) {
            return;
        }
        final String name = world.getAsString();
        if (!name.isBlank() && !loaded.test(name)) {
            throw new SettingRefused(
                    at + ".world", SettingRule.KNOWN, name, at + ": the world '" + name + "' is not on this server");
        }
    }
}
