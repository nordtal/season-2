package eu.nordtal.season.stewardagent.gamedata;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Box models for renderers the game draws in code, laid out on their entity textures as the game unfolds a box. */
final class StandIns {

    /** A chest: its body, its lid and the lock, on a 64 pixel chest texture named {@code #chest}. */
    static final JsonArray CHEST = elements(
            box(new double[] {1, 0, 1, 15, 10, 15}, 0, 19, 14, 10, 14, false),
            box(new double[] {1, 9, 1, 15, 14, 15}, 0, 0, 14, 5, 14, true),
            box(new double[] {7, 7, 15, 9, 11, 16}, 0, 0, 2, 4, 1, true));

    private StandIns() {}

    private static JsonArray elements(final JsonObject... boxes) {
        final JsonArray elements = new JsonArray();
        for (final JsonObject box : boxes) {
            elements.add(box);
        }
        return elements;
    }

    /**
     * One box from its corners and the texture offset and size the entity model gives it.
     *
     * The sides are unfolded left to right as west, north, east and south below a strip that holds the top.
     */
    private static JsonObject box(
            final double[] corners,
            final int u,
            final int v,
            final int w,
            final int h,
            final int d,
            final boolean top) {
        final JsonObject faces = new JsonObject();
        faces.add("west", face(u, v + d, u + d, v + d + h));
        faces.add("north", face(u + d, v + d, u + d + w, v + d + h));
        faces.add("east", face(u + d + w, v + d, u + d + w + d, v + d + h));
        faces.add("south", face(u + d + w + d, v + d, u + d + w + d + w, v + d + h));
        if (top) {
            faces.add("up", face(u + d + w, v, u + d + w + w, v + d));
        }
        final JsonObject box = new JsonObject();
        box.add("from", vector(corners[0], corners[1], corners[2]));
        box.add("to", vector(corners[3], corners[4], corners[5]));
        box.add("faces", faces);
        return box;
    }

    /** A face's area, given in texture pixels and kept as a model keeps it, in sixteenths of a 64 pixel texture. */
    private static JsonObject face(final int u1, final int v1, final int u2, final int v2) {
        final JsonObject face = new JsonObject();
        final JsonArray uv = new JsonArray();
        for (final int pixel : new int[] {u1, v1, u2, v2}) {
            uv.add(pixel / 4.0);
        }
        face.add("uv", uv);
        face.addProperty("texture", "#chest");
        return face;
    }

    private static JsonArray vector(final double x, final double y, final double z) {
        final JsonArray vector = new JsonArray();
        vector.add(x);
        vector.add(y);
        vector.add(z);
        return vector;
    }
}
