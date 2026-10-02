package eu.nordtal.s2.stewardagent.gamedata;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A model with its parents folded in: the textures, the elements and the GUI pose an icon is drawn from.
 *
 * @param textures every texture variable, the child's winning over its parent's
 * @param elements the boxes of the nearest model that has any, none for a flat item
 * @param gui the nearest {@code display.gui}, none for the identity
 * @param generated whether the chain ends in {@code item/generated}, which draws the layers flat
 */
record Model(
        Map<String, String> textures,
        @Nullable JsonArray elements,
        @Nullable Pose gui,
        boolean generated) {

    /** Parents are followed at most this far, so a loop in a broken pack ends. */
    private static final int DEEPEST = 32;

    /** Follows {@code id}'s parents in {@code assets}; a missing model is an empty one. */
    static Model resolve(final AssetSource assets, final String id) {
        final Map<String, String> textures = new HashMap<>();
        JsonArray elements = null;
        Pose gui = null;
        boolean generated = false;
        final Set<String> seen = new HashSet<>();
        String current = AssetSource.id(id);
        for (int depth = 0; depth < DEEPEST && seen.add(current); depth++) {
            if (current.equals("minecraft:builtin/generated") || current.equals("minecraft:item/generated")) {
                generated = true;
            }
            final JsonObject model = assets.json(AssetSource.path(current, "models", ".json"));
            if (model == null) {
                break;
            }
            if (model.get("textures") instanceof JsonObject declared) {
                for (final Map.Entry<String, JsonElement> texture : declared.entrySet()) {
                    final String sprite = sprite(texture.getValue());
                    if (sprite != null) {
                        textures.putIfAbsent(texture.getKey(), sprite);
                    }
                }
            }
            if (elements == null && model.get("elements") instanceof JsonArray boxes) {
                elements = boxes;
            }
            if (gui == null
                    && model.get("display") instanceof JsonObject display
                    && display.get("gui") instanceof JsonObject pose) {
                gui = Pose.of(pose);
            }
            if (!(model.get("parent") instanceof JsonPrimitive parent)) {
                break;
            }
            current = AssetSource.id(parent.getAsString());
        }
        return new Model(Map.copyOf(textures), elements, gui, generated);
    }

    /** A texture entry's sprite: the plain string, or the {@code sprite} of an object such as a glass pane's. */
    private static @Nullable String sprite(final JsonElement entry) {
        if (entry instanceof JsonObject declared) {
            return declared.get("sprite") instanceof JsonPrimitive sprite ? sprite.getAsString() : null;
        }
        return entry instanceof JsonPrimitive reference ? reference.getAsString() : null;
    }

    /** The texture id a reference such as {@code #side} ends at, or none for one nothing names. */
    @Nullable
    String texture(final String reference) {
        String current = reference;
        for (int depth = 0; depth < DEEPEST && current.startsWith("#"); depth++) {
            current = textures.get(current.substring(1));
            if (current == null) {
                return null;
            }
        }
        return current.startsWith("#") ? null : AssetSource.id(current);
    }

    /** A {@code display} entry: scaled, rotated about z, then y, then x in degrees, then moved in pixels. */
    record Pose(Vec3 rotation, Vec3 translation, Vec3 scale) {

        static final Pose IDENTITY = new Pose(Vec3.ZERO, Vec3.ZERO, new Vec3(1, 1, 1));

        static Pose of(final JsonObject pose) {
            return new Pose(
                    Vec3.of(pose.get("rotation"), 0),
                    Vec3.of(pose.get("translation"), 0),
                    Vec3.of(pose.get("scale"), 1));
        }

        /** Where a point in model pixels, centred on the block's middle, lands. */
        Vec3 apply(final Vec3 centred) {
            return centred.times(scale)
                    .rotateZ(rotation.z())
                    .rotateY(rotation.y())
                    .rotateX(rotation.x())
                    .plus(translation);
        }

        /** Where a direction points afterwards, which a scale does not change. */
        Vec3 turn(final Vec3 direction) {
            return direction.rotateZ(rotation.z()).rotateY(rotation.y()).rotateX(rotation.x());
        }
    }
}
