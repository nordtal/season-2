package eu.nordtal.season.stewardagent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.OptionalInt;

/** Reads the protocol number out of the raw answer to a Minecraft status ping. */
final class StatusAnswer {

    private StatusAnswer() {}

    /**
     * Returns {@code version.protocol} of the status JSON, which follows the packet's length prefixes.
     *
     * @param raw the bytes the server sent, as text
     * @return the number, or none when the answer holds no status
     */
    static OptionalInt protocol(final String raw) {
        final int end = raw.lastIndexOf('}');
        // A length prefix can itself be the byte of an opening brace, so each brace before the JSON is tried.
        for (int start = raw.indexOf('{'); start >= 0 && start < end; start = raw.indexOf('{', start + 1)) {
            final OptionalInt protocol = parsed(raw.substring(start, end + 1));
            if (protocol.isPresent()) {
                return protocol;
            }
        }
        return OptionalInt.empty();
    }

    private static OptionalInt parsed(final String json) {
        try {
            final JsonElement status = JsonParser.parseString(json);
            if (!status.isJsonObject()) {
                return OptionalInt.empty();
            }
            final JsonObject version = status.getAsJsonObject().getAsJsonObject("version");
            if (version == null || !version.has("protocol") || !version.get("protocol").isJsonPrimitive()) {
                return OptionalInt.empty();
            }
            return OptionalInt.of(version.get("protocol").getAsInt());
        } catch (final JsonParseException | ClassCastException | NumberFormatException | IllegalStateException bad) {
            return OptionalInt.empty();
        }
    }
}
