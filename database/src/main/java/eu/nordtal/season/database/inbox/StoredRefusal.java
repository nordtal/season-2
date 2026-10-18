package eu.nordtal.season.database.inbox;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.Refusal;
import eu.nordtal.season.messages.RefusalReason;
import java.util.LinkedHashMap;
import java.util.Map;

/** A {@link Refusal} as stored in the outcome of an inbox row: reason name, message key and values. */
final class StoredRefusal {

    /** A reason read back from a row, which knows its name but not its enum. */
    private record Named(String name) implements RefusalReason {}

    private record Stored(String reason, String key, Map<String, Object> args) {}

    private StoredRefusal() {}

    static String write(final Refusal refusal) {
        return Json.encode(new Stored(
                refusal.reason().name(),
                refusal.message().key(),
                refusal.message().args()));
    }

    static Refusal read(final String outcome) {
        final JsonObject stored = Json.tree(outcome).getAsJsonObject();
        final Map<String, Object> args = new LinkedHashMap<>();
        final JsonElement values = stored.get("args");
        if (values != null && values.isJsonObject()) {
            values.getAsJsonObject().entrySet().forEach(entry -> args.put(entry.getKey(), value(entry.getValue())));
        }
        return new Refusal(
                new Named(stored.get("reason").getAsString()),
                new MessageRef(stored.get("key").getAsString(), args));
    }

    /** Returns a number without fraction as a whole number, so {@code 3} is not {@code 3.0}. */
    private static Object value(final JsonElement element) {
        if (element.isJsonPrimitive()) {
            final JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                final double number = primitive.getAsDouble();
                return number == Math.rint(number) && !Double.isInfinite(number)
                        ? (Object) primitive.getAsLong()
                        : (Object) number;
            }
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }
            return primitive.getAsString();
        }
        return element.toString();
    }
}
