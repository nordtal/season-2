package eu.nordtal.s2.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.messages.text.Filling;
import eu.nordtal.s2.messages.text.MessageText;
import eu.nordtal.s2.messages.text.NodeJson;
import eu.nordtal.s2.messages.text.PlainText;
import eu.nordtal.s2.messages.value.Words;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * The vectors the browser's web target is tested on: the tree this parser hands it and the plain text it must show.
 * The frontend's texts test reads the same file, so both sides agree on the shape and on every choice made.
 */
class WebTargetVectorsTest {

    private static final Words WORDS = Messages.load("messages/test")
            .prepare(Viewer.of(Locale.ENGLISH), new MessageRef("plain", Map.of()))
            .words();

    @Test
    void eachTextParsesToTheTreeTheBrowserWalks() throws IOException {
        final List<String> wrong = new ArrayList<>();
        for (final JsonElement element : vectors()) {
            final JsonObject vector = element.getAsJsonObject();
            final MessageText text = MessageText.parse(vector.get("text").getAsString(), markup(vector));
            final JsonElement tree = Json.tree(Json.encode(NodeJson.of(text.nodes())));
            if (!tree.equals(vector.get("nodes"))) {
                wrong.add(vector.get("text").getAsString() + " parses to " + tree);
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    void eachTextShowsWhatTheBrowserMustShow() throws IOException {
        final List<String> wrong = new ArrayList<>();
        for (final JsonElement element : vectors()) {
            final JsonObject vector = element.getAsJsonObject();
            final MessageText text = MessageText.parse(vector.get("text").getAsString(), markup(vector));
            final Map<String, Object> args = MessageJson.decodeArgs(asRead(vector.get("args")));
            final String shown =
                    PlainText.of(Filling.fill(text, args, Map.of(), name -> {}), Locale.ENGLISH, ZoneOffset.UTC, WORDS);
            if (!shown.equals(vector.get("plain").getAsString())) {
                wrong.add(vector.get("text").getAsString() + " with " + vector.get("args") + " shows " + shown);
            }
        }
        assertEquals(List.of(), wrong);
    }

    /** A choice's word comes back as text, which a select chooses on and shows exactly as it did the choice. */
    @Test
    void aMessageReadsBackAsItWasWritten() throws IOException {
        for (final JsonElement element : vectors()) {
            final JsonObject args = element.getAsJsonObject().get("args").getAsJsonObject();
            final JsonObject expected = JsonParser.parseString(args.toString()
                            .replaceAll(
                                    "\\{\"kind\":\"choice\",\"value\":(\"[^\"]*\")}",
                                    "{\"kind\":\"text\",\"value\":$1}"))
                    .getAsJsonObject();
            assertEquals(
                    expected,
                    Json.tree(Json.encode(MessageJson.encodeArgs(MessageJson.decodeArgs(asRead(args))))),
                    args.toString());
        }
    }

    /** Whether the vector is a text with MiniMessage tags, as a Minecraft text is; Steward's own have none. */
    private static boolean markup(final JsonObject vector) {
        return vector.has("markup") && vector.get("markup").getAsBoolean();
    }

    /** The arguments as Steward or a row hands them over: read back by the one codec into maps. */
    private static Map<?, ?> asRead(final JsonElement args) {
        return Json.decode(args.toString(), Map.class);
    }

    private static JsonArray vectors() throws IOException {
        try (InputStream in = Objects.requireNonNull(
                WebTargetVectorsTest.class.getResourceAsStream("/web-target.json"), "web-target.json")) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonArray();
        }
    }
}
