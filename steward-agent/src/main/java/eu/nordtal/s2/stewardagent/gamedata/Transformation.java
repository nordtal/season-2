package eu.nordtal.s2.stewardagent.gamedata;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

/**
 * An item definition's {@code transformation} of a model, in blocks: right rotation, scale, left rotation, move.
 *
 * Points go in and out in model pixels; a composite's parts, such as a bed's two halves, are placed with it.
 */
record Transformation(
        Vec3 translation,
        Quaternion left,
        Vec3 scale,
        Quaternion right,
        @Nullable Transformation inner) {

    static final Transformation NONE =
            new Transformation(Vec3.ZERO, Quaternion.IDENTITY, new Vec3(1, 1, 1), Quaternion.IDENTITY, null);

    static Transformation of(final @Nullable JsonElement element) {
        if (!(element instanceof JsonObject declared)) {
            return NONE;
        }
        return new Transformation(
                Vec3.of(declared.get("translation"), 0),
                Quaternion.of(declared.get("left_rotation")),
                Vec3.of(declared.get("scale"), 1),
                Quaternion.of(declared.get("right_rotation")),
                null);
    }

    /** This one first and {@code next} after it, as a branch inside another is placed by both. */
    Transformation then(final Transformation next) {
        if (next.equals(NONE)) {
            return this;
        }
        if (equals(NONE)) {
            return next;
        }
        return new Transformation(next.translation, next.left, next.scale, next.right, this);
    }

    Vec3 apply(final Vec3 pixels) {
        final Vec3 inside = inner == null ? pixels : inner.apply(pixels);
        if (equals(NONE)) {
            return inside;
        }
        final Vec3 blocks = inside.times(1 / 16.0);
        final Vec3 moved = left.rotate(right.rotate(blocks).times(scale)).plus(translation);
        return moved.times(16);
    }

    /** A unit quaternion as a model file writes one, {@code [x, y, z, w]}. */
    record Quaternion(double x, double y, double z, double w) {

        static final Quaternion IDENTITY = new Quaternion(0, 0, 0, 1);

        static Quaternion of(final @Nullable JsonElement element) {
            if (element instanceof JsonArray array && array.size() == 4) {
                return new Quaternion(
                        array.get(0).getAsDouble(),
                        array.get(1).getAsDouble(),
                        array.get(2).getAsDouble(),
                        array.get(3).getAsDouble());
            }
            return IDENTITY;
        }

        Vec3 rotate(final Vec3 vector) {
            return vector.rotate(x, y, z, w);
        }
    }
}
