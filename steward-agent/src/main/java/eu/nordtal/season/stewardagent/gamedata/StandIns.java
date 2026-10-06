package eu.nordtal.season.stewardagent.gamedata;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

/** Box models for renderers the game draws in code, laid out on their entity textures as the game unfolds a box. */
final class StandIns {

    /** A chest: its body, its lid and the lock, on a 64 pixel chest texture named {@code #chest}. */
    static final JsonArray CHEST = elements(
            box(new double[] {1, 0, 1, 15, 10, 15}, 0, 19, 14, 10, 14, null, "#chest", 0),
            box(new double[] {1, 9, 1, 15, 14, 15}, 0, 0, 14, 5, 14, "up", "#chest", 0),
            box(new double[] {7, 7, 15, 9, 11, 16}, 0, 0, 2, 4, 1, "up", "#chest", 0));

    /**
     * A banner in entity pixels with y down: pole and bar on {@code #pole}, the cloth on {@code #flag} with tint 0.
     *
     * The item's transformation turns it upright, so every face's texture turns half round with it.
     */
    static final JsonArray BANNER = elements(
            tinted(box(new double[] {-1, -42, -1, 1, 0, 1}, 44, 0, 2, 42, 2, "down", "#pole", 180), -1),
            tinted(box(new double[] {-10, -44, -1, 10, -42, 1}, 0, 42, 20, 2, 2, "down", "#pole", 180), -1),
            tinted(box(new double[] {-10, -44, -2, 10, -4, -1}, 0, 0, 20, 40, 1, "down", "#flag", 180), 0));

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
     * The sides unfold as west, north, east and south below the ends; {@code cap} is the end drawn, if any.
     */
    private static JsonObject box(
            final double[] corners,
            final int u,
            final int v,
            final int w,
            final int h,
            final int d,
            final @Nullable String cap,
            final String texture,
            final int rotation) {
        final JsonObject faces = new JsonObject();
        faces.add("west", face(u, v + d, u + d, v + d + h, texture, rotation));
        faces.add("north", face(u + d, v + d, u + d + w, v + d + h, texture, rotation));
        faces.add("east", face(u + d + w, v + d, u + d + w + d, v + d + h, texture, rotation));
        faces.add("south", face(u + d + w + d, v + d, u + d + w + d + w, v + d + h, texture, rotation));
        if (cap != null) {
            final int from = cap.equals("up") ? u + d + w : u + d;
            faces.add(cap, face(from, v, from + w, v + d, texture, rotation));
        }
        final JsonObject box = new JsonObject();
        box.add("from", vector(corners[0], corners[1], corners[2]));
        box.add("to", vector(corners[3], corners[4], corners[5]));
        box.add("faces", faces);
        return box;
    }

    /** A face's area, given in texture pixels and kept as a model keeps it, in sixteenths of a 64 pixel texture. */
    private static JsonObject face(
            final int u1, final int v1, final int u2, final int v2, final String texture, final int rotation) {
        final JsonObject face = new JsonObject();
        final JsonArray uv = new JsonArray();
        for (final int pixel : new int[] {u1, v1, u2, v2}) {
            uv.add(pixel / 4.0);
        }
        face.add("uv", uv);
        face.addProperty("texture", texture);
        if (rotation != 0) {
            face.addProperty("rotation", rotation);
        }
        return face;
    }

    /** The box with every face tinted by tint {@code index}, or left as drawn for {@code -1}. */
    private static JsonObject tinted(final JsonObject box, final int index) {
        if (index >= 0 && box.get("faces") instanceof JsonObject faces) {
            for (final String side : faces.keySet()) {
                faces.getAsJsonObject(side).addProperty("tintindex", index);
            }
        }
        return box;
    }

    private static JsonArray vector(final double x, final double y, final double z) {
        final JsonArray vector = new JsonArray();
        vector.add(x);
        vector.add(y);
        vector.add(z);
        return vector;
    }
}
