package eu.nordtal.season.messages.document;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.messages.MessageJson;
import eu.nordtal.season.messages.text.MessageCheck;
import eu.nordtal.season.messages.value.Kind;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Checks each shared vector's document against its declaration, and its values against their kinds.
 * The bot's renderer and Steward's preview check the components.
 */
class DiscordDocumentVectorsTest {

    @Test
    void eachDocumentFitsItsDeclaration() throws IOException {
        final List<String> problems = new ArrayList<>();
        for (final JsonObject vector : vectors()) {
            DocumentCheck.errors(declaration(vector), document(vector), MessageCheck.Mode.PACKAGED)
                    .forEach(problem -> problems.add(vector.get("name").getAsString() + ": " + problem));
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void eachValueAndItemIsOfTheKindItsDeclarationNames() throws IOException {
        final List<String> wrong = new ArrayList<>();
        for (final JsonObject vector : vectors()) {
            final DocumentDeclaration declaration = declaration(vector);
            values(declaration.name(), vector.getAsJsonObject("values"), declaration.values(), wrong);
            for (final Map.Entry<String, JsonElement> slot :
                    vector.getAsJsonObject("slots").entrySet()) {
                final Slot declared = declaration.requireSlot(slot.getKey());
                final JsonObject filled = slot.getValue().getAsJsonObject();
                if (filled.has("items")) {
                    filled.getAsJsonArray("items")
                            .forEach(item -> values(slot.getKey(), item.getAsJsonObject(), declared.values(), wrong));
                }
                if (filled.has("options")) {
                    filled.getAsJsonArray("options")
                            .forEach(option -> values(
                                    slot.getKey(),
                                    option.getAsJsonObject().getAsJsonObject("args"),
                                    declared.values(),
                                    wrong));
                }
            }
        }
        assertEquals(List.of(), wrong);
    }

    private static void values(
            final String where, final JsonObject json, final Map<String, Value> declared, final List<String> wrong) {
        MessageJson.decodeArgs(Json.decode(json.toString(), Map.class)).forEach((name, value) -> {
            final Value named = declared.get(name);
            if (named == null
                    || Kind.ofValue(value)
                            .map(Kind::token)
                            .filter(named.kind()::equals)
                            .isEmpty()) {
                wrong.add(where + ": {" + name + "} is filled as " + json.get(name) + ", declared as " + named);
            }
        });
    }

    private static DocumentDeclaration declaration(final JsonObject vector) {
        return Json.decode(vector.get("declaration").toString(), DocumentDeclaration.class);
    }

    private static Document document(final JsonObject vector) {
        return Document.decode(vector.get("document").toString());
    }

    private static List<JsonObject> vectors() throws IOException {
        try (InputStream in = Objects.requireNonNull(
                DiscordDocumentVectorsTest.class.getResourceAsStream("/discord-documents.json"),
                "discord-documents.json")) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonArray()
                    .asList()
                    .stream()
                    .map(JsonElement::getAsJsonObject)
                    .toList();
        }
    }
}
