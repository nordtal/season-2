package eu.nordtal.s2.steward;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.database.alert.Alert;
import eu.nordtal.s2.database.alert.AlertChannel;
import eu.nordtal.s2.database.alert.AlertType;
import eu.nordtal.s2.messages.MessageJson;
import eu.nordtal.s2.messages.MessageRef;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The JSON steward's API speaks: the shared codec, with the enums the browser spells in lowercase.
 *
 * The TypeScript types read {@link #LOWERCASE} too; a message travels as {@link MessageJson} writes it, typed.
 */
public final class WireJson {

    /** The enums written and read as their constant in lowercase. */
    public static final List<Class<? extends Enum<?>>> LOWERCASE =
            List.of(AlertType.class, AlertChannel.class, Alert.Level.class);

    private static final Gson GSON = build();

    private WireJson() {}

    /** Returns the one codec of steward's routes. */
    public static Gson gson() {
        return GSON;
    }

    /** Returns how a constant is spelled on the wire. */
    public static String spelled(final Enum<?> constant) {
        return LOWERCASE.contains(constant.getDeclaringClass())
                ? constant.name().toLowerCase(Locale.ROOT)
                : constant.name();
    }

    private static Gson build() {
        final GsonBuilder builder = Json.gson().newBuilder();
        for (final Class<? extends Enum<?>> type : LOWERCASE) {
            register(builder, type);
        }
        builder.registerTypeAdapter(MessageRef.class, new Typed().nullSafe());
        return builder.create();
    }

    private static <E extends Enum<?>> void register(final GsonBuilder builder, final Class<E> type) {
        builder.registerTypeAdapter(type, new Lowercase<>(type).nullSafe());
    }

    private static final class Lowercase<E extends Enum<?>> extends TypeAdapter<E> {
        private final Class<E> type;

        Lowercase(final Class<E> type) {
            this.type = type;
        }

        @Override
        public void write(final JsonWriter out, final E value) throws IOException {
            out.value(spelled(value));
        }

        @Override
        public E read(final JsonReader in) throws IOException {
            final String text = in.nextString();
            for (final E constant : type.getEnumConstants()) {
                if (spelled(constant).equals(text)) {
                    return constant;
                }
            }
            throw new IOException("no " + type.getSimpleName() + " is spelled " + text);
        }
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
