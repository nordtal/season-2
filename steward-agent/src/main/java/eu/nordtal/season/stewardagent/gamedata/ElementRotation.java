package eu.nordtal.season.stewardagent.gamedata;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;

/** A box's own rotation about a point, declared about one axis or about each of x, y and z. */
record ElementRotation(Vec3 origin, Vec3 angles) {

    static final ElementRotation NONE = new ElementRotation(new Vec3(8, 8, 8), Vec3.ZERO);

    static ElementRotation of(final @Nullable JsonElement element) {
        if (!(element instanceof JsonObject rotation)) {
            return NONE;
        }
        final Vec3 origin = Vec3.of(rotation.get("origin"), 8);
        if (rotation.get("axis") instanceof JsonPrimitive axis
                && rotation.get("angle") instanceof JsonPrimitive angle) {
            final double degrees = angle.getAsDouble();
            return new ElementRotation(
                    origin,
                    switch (axis.getAsString()) {
                        case "x" -> new Vec3(degrees, 0, 0);
                        case "y" -> new Vec3(0, degrees, 0);
                        default -> new Vec3(0, 0, degrees);
                    });
        }
        return new ElementRotation(origin, new Vec3(angle(rotation, "x"), angle(rotation, "y"), angle(rotation, "z")));
    }

    Vec3 apply(final Vec3 point) {
        return point.minus(origin)
                .rotateX(angles.x())
                .rotateY(angles.y())
                .rotateZ(angles.z())
                .plus(origin);
    }

    private static double angle(final JsonObject rotation, final String axis) {
        return rotation.get(axis) instanceof JsonPrimitive value ? value.getAsDouble() : 0;
    }
}
