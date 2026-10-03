package eu.nordtal.s2.database;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.messages.MessageJson;
import eu.nordtal.s2.messages.MessageRef;
import java.util.Map;

/**
 * The one codec with a message in it: the kernel's, and a {@link MessageRef} typed as {@link MessageJson} writes it.
 * A journal line, an alert and an inbox payload store messages this way, and Steward's API speaks the same.
 */
public final class DatabaseJson {

    private static final Gson GSON = Json.gson()
            .newBuilder()
            .registerTypeHierarchyAdapter(MessageRef.class, new Typed().nullSafe())
            .create();

    private DatabaseJson() {}

    /** Returns {@code value} as compact JSON. */
    public static String encode(final Object value) {
        return GSON.toJson(value);
    }

    /** Reads {@code json} as {@code type}. */
    public static <T> T decode(final String json, final Class<T> type) {
        return GSON.fromJson(json, type);
    }

    /** Reads {@code json} as a generic type, such as a list of messages. */
    public static <T> T decode(final String json, final TypeToken<T> type) {
        return GSON.fromJson(json, type);
    }

    /** Returns the codec itself, for a codec built on it. */
    public static Gson gson() {
        return GSON;
    }

    private static final class Typed extends TypeAdapter<MessageRef> {

        @Override
        public void write(final JsonWriter out, final MessageRef value) {
            Json.gson().toJson(MessageJson.encode(value), Map.class, out);
        }

        @Override
        public MessageRef read(final JsonReader in) {
            return MessageJson.decode(Json.gson().<Map<?, ?>>fromJson(in, Map.class));
        }
    }
}
