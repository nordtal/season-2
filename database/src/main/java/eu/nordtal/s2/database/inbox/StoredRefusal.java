package eu.nordtal.s2.database.inbox;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Refusal;
import eu.nordtal.s2.messages.RefusalReason;
import java.util.LinkedHashMap;
import java.util.Map;

/** A {@link Refusal} as an inbox row's outcome stores it: the reason's name and the message's key and values. */
final class StoredRefusal {

    /** A reason read back from a row, which knows its name and not its enum. */
    private record Named(String name) implements RefusalReason {}

    /** The stored shape. */
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

    /** A number comes back as the whole number it was when it has no fraction, so {@code 3} is not {@code 3.0}. */
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
