package eu.nordtal.season.stewardagent.gamedata;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Locale;

/** The six faces of a box, each with its corners in the game's order and the texture area it shows by default. */
enum Face {
    DOWN,
    UP,
    NORTH,
    SOUTH,
    WEST,
    EAST;

    /** How the face is named in a model's {@code faces}. */
    String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The four corners, in the order whose texture corners are (u1, v1), (u1, v2), (u2, v2), (u2, v1). */
    Vec3[] corners(final Vec3 from, final Vec3 to) {
        final double x1 = from.x();
        final double y1 = from.y();
        final double z1 = from.z();
        final double x2 = to.x();
        final double y2 = to.y();
        final double z2 = to.z();
        return switch (this) {
            case DOWN ->
                new Vec3[] {new Vec3(x1, y1, z2), new Vec3(x1, y1, z1), new Vec3(x2, y1, z1), new Vec3(x2, y1, z2)};
            case UP ->
                new Vec3[] {new Vec3(x1, y2, z1), new Vec3(x1, y2, z2), new Vec3(x2, y2, z2), new Vec3(x2, y2, z1)};
            case NORTH ->
                new Vec3[] {new Vec3(x2, y2, z1), new Vec3(x2, y1, z1), new Vec3(x1, y1, z1), new Vec3(x1, y2, z1)};
            case SOUTH ->
                new Vec3[] {new Vec3(x1, y2, z2), new Vec3(x1, y1, z2), new Vec3(x2, y1, z2), new Vec3(x2, y2, z2)};
            case WEST ->
                new Vec3[] {new Vec3(x1, y2, z1), new Vec3(x1, y1, z1), new Vec3(x1, y1, z2), new Vec3(x1, y2, z2)};
            case EAST ->
                new Vec3[] {new Vec3(x2, y2, z2), new Vec3(x2, y1, z2), new Vec3(x2, y1, z1), new Vec3(x2, y2, z1)};
        };
    }

    /** The texture corner each of the four corners shows, in sixteenths, turned by the face's {@code rotation}. */
    double[][] uv(final JsonObject face, final Vec3 from, final Vec3 to) {
        final double[] area = face.get("uv") instanceof JsonArray declared && declared.size() == 4
                ? new double[] {
                    declared.get(0).getAsDouble(),
                    declared.get(1).getAsDouble(),
                    declared.get(2).getAsDouble(),
                    declared.get(3).getAsDouble()
                }
                : defaultUv(from, to);
        final double[][] corners = {{area[0], area[1]}, {area[0], area[3]}, {area[2], area[3]}, {area[2], area[1]}};
        final int turns = face.get("rotation") instanceof JsonPrimitive rotation ? rotation.getAsInt() / 90 : 0;
        final double[][] turned = new double[4][];
        for (int i = 0; i < 4; i++) {
            turned[i] = corners[Math.floorMod(i + turns, 4)];
        }
        return turned;
    }

    private double[] defaultUv(final Vec3 from, final Vec3 to) {
        return switch (this) {
            case DOWN -> new double[] {from.x(), 16 - to.z(), to.x(), 16 - from.z()};
            case UP -> new double[] {from.x(), from.z(), to.x(), to.z()};
            case NORTH -> new double[] {16 - to.x(), 16 - to.y(), 16 - from.x(), 16 - from.y()};
            case SOUTH -> new double[] {from.x(), 16 - to.y(), to.x(), 16 - from.y()};
            case WEST -> new double[] {from.z(), 16 - to.y(), to.z(), 16 - from.y()};
            case EAST -> new double[] {16 - to.z(), 16 - to.y(), 16 - from.z(), 16 - from.y()};
        };
    }
}
