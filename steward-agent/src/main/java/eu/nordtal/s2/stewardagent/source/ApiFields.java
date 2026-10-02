package eu.nordtal.s2.stewardagent.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import eu.nordtal.s2.common.json.Json;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

/** Reads the three APIs' JSON with raw gson, naming the field in the message when it is missing. */
final class ApiFields {

    private ApiFields() {}

    static JsonObject object(final String body, final String what) throws IOException {
        return element(body, what).getAsJsonObject();
    }

    static JsonArray array(final String body, final String what) throws IOException {
        final JsonElement element = element(body, what);
        if (!element.isJsonArray()) {
            throw new IOException(
                    what + ": expected a JSON array, got " + element.getClass().getSimpleName());
        }
        return element.getAsJsonArray();
    }

    private static JsonElement element(final String body, final String what) throws IOException {
        try {
            return Json.tree(body);
        } catch (final JsonSyntaxException malformed) {
            throw new IOException(what + ": the response was not JSON", malformed);
        }
    }

    static String string(final JsonObject object, final String field, final String what) throws IOException {
        final String value = optionalString(object, field);
        if (value == null) {
            throw new IOException(what + ": no '" + field + "' in the response. The API's shape has"
                    + " changed, or this is not the endpoint we think it is.");
        }
        return value;
    }

    static @Nullable String optionalString(final JsonObject object, final String field) {
        final JsonElement element = object.get(field);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    static boolean bool(final JsonObject object, final String field, final boolean fallback) {
        final JsonElement element = object.get(field);
        return (element == null || element.isJsonNull()) ? fallback : element.getAsBoolean();
    }

    static long number(final JsonObject object, final String field, final long fallback) {
        final JsonElement element = object.get(field);
        return element == null || element.isJsonNull() ? fallback : element.getAsLong();
    }

    static @Nullable JsonObject child(final JsonObject object, final String field) {
        final JsonElement element = object.get(field);
        return element == null || !element.isJsonObject() ? null : element.getAsJsonObject();
    }
}
