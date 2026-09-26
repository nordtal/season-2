package eu.nordtal.s2.steward.worker.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

/**
 * Reading the three APIs' JSON, with the field name in the message when it is not there.
 * <p>
 * gson arrives through jcore as a transitive api dependency, which is also why no parser is
 * declared in this module's build file. It is used raw rather than through {@code @SerializedName}
 * data classes on purpose: these payloads are large, mostly irrelevant, and change shape upstream
 * without warning. Naming the six fields we actually read means an upstream addition is ignored
 * and an upstream <em>removal</em> is an error that says which field went missing - a mapped class
 * would instead hand back an object with a silent null in it.
 * </p>
 */
final class Json {

    private Json() {}

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
            return JsonParser.parseString(body);
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
        return element == null || element.isJsonNull() ? fallback : element.getAsBoolean();
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
