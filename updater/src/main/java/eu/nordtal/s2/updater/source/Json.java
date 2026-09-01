package eu.nordtal.s2.updater.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

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

    private Json() {
    }

    static @NotNull JsonObject object(final @NotNull String body, final @NotNull String what) throws IOException {
        return element(body, what).getAsJsonObject();
    }

    static @NotNull JsonArray array(final @NotNull String body, final @NotNull String what) throws IOException {
        final JsonElement element = element(body, what);
        if (!element.isJsonArray()) {
            throw new IOException(what + ": expected a JSON array, got " + element.getClass().getSimpleName());
        }
        return element.getAsJsonArray();
    }

    private static @NotNull JsonElement element(final @NotNull String body, final @NotNull String what)
            throws IOException {
        try {
            return JsonParser.parseString(body);
        } catch (final JsonSyntaxException malformed) {
            throw new IOException(what + ": the response was not JSON", malformed);
        }
    }

    static @NotNull String string(final @NotNull JsonObject object, final @NotNull String field,
                                  final @NotNull String what) throws IOException {
        final String value = optionalString(object, field);
        if (value == null) {
            throw new IOException(what + ": no '" + field + "' in the response. The API's shape has"
                    + " changed, or this is not the endpoint we think it is.");
        }
        return value;
    }

    static @Nullable String optionalString(final @NotNull JsonObject object, final @NotNull String field) {
        final JsonElement element = object.get(field);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    static boolean bool(final @NotNull JsonObject object, final @NotNull String field, final boolean fallback) {
        final JsonElement element = object.get(field);
        return element == null || element.isJsonNull() ? fallback : element.getAsBoolean();
    }

    static long number(final @NotNull JsonObject object, final @NotNull String field, final long fallback) {
        final JsonElement element = object.get(field);
        return element == null || element.isJsonNull() ? fallback : element.getAsLong();
    }

    static @Nullable JsonObject child(final @NotNull JsonObject object, final @NotNull String field) {
        final JsonElement element = object.get(field);
        return element == null || !element.isJsonObject() ? null : element.getAsJsonObject();
    }
}
