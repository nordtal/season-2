package eu.nordtal.season.common.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.id.PlayerId;
import java.io.IOException;
import java.io.Reader;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The JSON codec over Gson. It leaves out a {@code null} component and writes an id, an instant and a duration as text.
 */
public final class Json {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .registerTypeAdapter(DiscordId.class, new TextAdapter<>(DiscordId::value, DiscordId::of))
            .registerTypeAdapter(
                    PlayerId.class, new TextAdapter<>(PlayerId::toString, text -> PlayerId.of(UUID.fromString(text))))
            .registerTypeAdapter(Instant.class, new TextAdapter<>(Instant::toString, Instant::parse))
            .registerTypeAdapter(Duration.class, new TextAdapter<>(Duration::toString, Duration::parse))
            .create();

    private Json() {}

    public static String encode(final Object value) {
        return GSON.toJson(value);
    }

    /**
     * Reads {@code json} as {@code type}. A missing component is {@code null} or zero.
     *
     * @throws com.google.gson.JsonParseException if the text is not JSON of that shape
     */
    public static <T> T decode(final String json, final Class<T> type) {
        return GSON.fromJson(json, type);
    }

    public static <T> T decode(final String json, final TypeToken<T> type) {
        return GSON.fromJson(json, type);
    }

    public static <T> T decode(final Reader json, final Class<T> type) {
        return GSON.fromJson(json, type);
    }

    public static JsonElement tree(final String json) {
        return JsonParser.parseString(json);
    }

    public static Gson gson() {
        return GSON;
    }

    private static final class TextAdapter<T> extends TypeAdapter<T> {

        private final Function<T, String> write;
        private final Function<String, T> read;

        TextAdapter(final Function<T, String> write, final Function<String, T> read) {
            this.write = write;
            this.read = read;
        }

        @Override
        public void write(final JsonWriter out, final @Nullable T value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(write.apply(value));
            }
        }

        @Override
        public @Nullable T read(final JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return read.apply(in.nextString());
        }
    }
}
