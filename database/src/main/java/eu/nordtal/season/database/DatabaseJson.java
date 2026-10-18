package eu.nordtal.season.database;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.messages.MessageJson;
import eu.nordtal.season.messages.MessageRef;
import java.util.Map;

/** The one codec that handles a {@link MessageRef} typed as {@link MessageJson}. */
public final class DatabaseJson {

    private static final Gson GSON = Json.gson()
            .newBuilder()
            .registerTypeHierarchyAdapter(MessageRef.class, new Typed().nullSafe())
            .create();

    private DatabaseJson() {}

    public static String encode(final Object value) {
        return GSON.toJson(value);
    }

    public static <T> T decode(final String json, final Class<T> type) {
        return GSON.fromJson(json, type);
    }

    public static <T> T decode(final String json, final TypeToken<T> type) {
        return GSON.fromJson(json, type);
    }

    /** Returns the codec itself. */
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
