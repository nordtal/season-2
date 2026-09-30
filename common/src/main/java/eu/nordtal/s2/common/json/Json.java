package eu.nordtal.s2.common.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.Reader;

/**
 * The one JSON codec: records, lists, maps and plain values to text and back.
 * On Gson, which Paper and Velocity ship, so it is never shaded; a {@code null} component is left out.
 */
public final class Json {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {}

    /** Returns {@code value} as compact JSON. */
    public static String encode(final Object value) {
        return GSON.toJson(value);
    }

    /**
     * Reads {@code json} as {@code type}; a missing component arrives as {@code null} or zero.
     *
     * @throws com.google.gson.JsonParseException if the text is not JSON of that shape
     */
    public static <T> T decode(final String json, final Class<T> type) {
        return GSON.fromJson(json, type);
    }

    /** Reads {@code json} as a generic type, such as {@code new TypeToken<Map<String, Object>>() {}}. */
    public static <T> T decode(final String json, final TypeToken<T> type) {
        return GSON.fromJson(json, type);
    }

    /** Reads a stream of JSON as {@code type}, leaving the reader open. */
    public static <T> T decode(final Reader json, final Class<T> type) {
        return GSON.fromJson(json, type);
    }

    /** Returns the tree of {@code json}, for a document whose shape is walked rather than bound. */
    public static JsonElement tree(final String json) {
        return JsonParser.parseString(json);
    }

    /** Returns the codec itself, for a web framework's mapper, which takes a Gson. */
    public static Gson gson() {
        return GSON;
    }
}
