package eu.nordtal.season.bridgemod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** One thing the bridge does in the client, read from a request's JSON object. */
sealed interface Request {

    /** Reloads every resource pack. */
    record Reload() implements Request {}

    /** Adds a text component to the chat. */
    record Chat(JsonElement text) implements Request {}

    /** Shows a text component above the hotbar. */
    record Actionbar(JsonElement text) implements Request {}

    /** Shows a title, and a subtitle under it when one is given. */
    record Title(JsonElement text, @Nullable JsonElement subtitle) implements Request {}

    /** Shows the bridge's one boss bar, or removes it when text is null. */
    record BossBar(@Nullable JsonElement text, String color, String overlay, float progress) implements Request {}

    /** Opens an empty chest of the given rows under a title, in the client only. */
    record Chest(JsonElement text, int rows) implements Request {}

    /** Puts an item, written as `/give` writes it, into the selected hotbar slot in creative mode. */
    record Give(String item, int count) implements Request {}

    /** Places a block state, written as `/setblock` writes it, in the client only. */
    record Block(String block, @Nullable Position pos) implements Request {}

    /** A block position in the world. */
    record Position(int x, int y, int z) {}

    /** Removes the boss bar, the title and every block the bridge placed. */
    record Clear() implements Request {}

    /** Reads the request named by `op`, or throws an IllegalArgumentException that says what is wrong. */
    static Request of(final JsonObject json) {
        final JsonElement name = json.get("op");
        if (!(name instanceof JsonPrimitive primitive) || !primitive.isString()) {
            throw new IllegalArgumentException("A request needs 'op' as a string.");
        }
        final String op = primitive.getAsString();
        return switch (op) {
            case "reload" -> new Reload();
            case "chat" -> new Chat(required(json, "text"));
            case "actionbar" -> new Actionbar(required(json, "text"));
            case "title" -> new Title(required(json, "text"), json.get("subtitle"));
            case "bossbar" ->
                new BossBar(
                        json.get("text"),
                        optionalString(json, "color", "white"),
                        optionalString(json, "overlay", "progress"),
                        (float) number(json, "progress", 1, 0, 1));
            case "chest" -> new Chest(required(json, "text"), (int) number(json, "rows", 3, 1, 6));
            case "give" -> new Give(string(json, "item"), (int) number(json, "count", 1, 1, 99));
            case "block" -> new Block(string(json, "block"), position(json));
            case "clear" -> new Clear();
            default -> throw new IllegalArgumentException("The bridge knows no op '" + op + "'.");
        };
    }

    private static JsonElement required(final JsonObject json, final String field) {
        final JsonElement value = json.get(field);
        if (value == null || value.isJsonNull()) {
            throw new IllegalArgumentException(op(json) + " needs '" + field + "'.");
        }
        return value;
    }

    private static String string(final JsonObject json, final String field) {
        final JsonElement value = required(json, field);
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isString()) {
            throw new IllegalArgumentException(op(json) + " needs '" + field + "' as a string.");
        }
        return primitive.getAsString();
    }

    private static String optionalString(final JsonObject json, final String field, final String fallback) {
        return json.has(field) ? string(json, field).toLowerCase(Locale.ROOT) : fallback;
    }

    private static double number(
            final JsonObject json, final String field, final double fallback, final double min, final double max) {
        if (!json.has(field)) {
            return fallback;
        }
        final JsonElement value = json.get(field);
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw new IllegalArgumentException(op(json) + " needs '" + field + "' as a number.");
        }
        final double number = primitive.getAsDouble();
        if (number < min || number > max) {
            throw new IllegalArgumentException(
                    op(json) + " needs '" + field + "' from " + fmt(min) + " to " + fmt(max) + ".");
        }
        return number;
    }

    private static @Nullable Position position(final JsonObject json) {
        if (!json.has("pos")) {
            return null;
        }
        final JsonElement value = json.get("pos");
        if (!value.isJsonArray() || value.getAsJsonArray().size() != 3) {
            throw new IllegalArgumentException("block needs 'pos' as [x, y, z].");
        }
        final int[] axes = new int[3];
        for (int i = 0; i < 3; i++) {
            final JsonElement axis = value.getAsJsonArray().get(i);
            if (!(axis instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
                throw new IllegalArgumentException("block needs 'pos' as [x, y, z].");
            }
            axes[i] = primitive.getAsInt();
        }
        return new Position(axes[0], axes[1], axes[2]);
    }

    private static String op(final JsonObject json) {
        return json.get("op").getAsString();
    }

    private static String fmt(final double number) {
        return number == Math.rint(number) ? Long.toString((long) number) : Double.toString(number);
    }
}
