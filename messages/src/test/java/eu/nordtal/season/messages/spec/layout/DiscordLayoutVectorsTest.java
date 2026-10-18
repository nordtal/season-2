package eu.nordtal.season.messages.spec.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.messages.MessageJson;
import eu.nordtal.season.messages.PackagedTexts;
import eu.nordtal.season.messages.spec.MessageSchema;
import eu.nordtal.season.messages.value.Kind;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The vectors a renderer of layouts is tested on.
 * Each gives a declaration, its texts, what the code fills in and the Discord components that come out. This side
 * holds the declaration and the filling to the specs; the bot's renderer and Steward's preview hold the components.
 */
class DiscordLayoutVectorsTest {

    private static final Function<String, Optional<MessageSchema.Bundle>> SCHEMAS =
            LayoutSchema.schemas(List.of(LayoutTexts.class));

    @Test
    void eachDeclarationIsSoundAgainstTheTextsItPlaces() throws IOException {
        final List<String> problems = new ArrayList<>();
        for (final JsonObject vector : vectors()) {
            problems.addAll(LayoutCheck.problems(List.of(layout(vector)), SCHEMAS));
        }
        assertEquals(List.of(), problems);
    }

    /** English names every key the layout shows; another language may name fewer. */
    @Test
    void eachVectorsTextsAreTheBundlesOwn() throws IOException {
        final List<String> wrong = new ArrayList<>();
        for (final JsonObject vector : vectors()) {
            final Layout layout = layout(vector);
            final Set<String> shows = new TreeSet<>(entry(layout).shows());
            for (final Map.Entry<String, JsonElement> language :
                    vector.getAsJsonObject("texts").entrySet()) {
                final Map<String, List<String>> bundle = Objects.requireNonNull(
                        PackagedTexts.read(getClass().getClassLoader(), "layouts", language.getKey()),
                        language.getKey());
                final JsonObject texts = language.getValue().getAsJsonObject();
                if (language.getKey().equals("en")
                        ? !texts.keySet().equals(shows)
                        : !shows.containsAll(texts.keySet())) {
                    wrong.add(layout.name() + " in " + language.getKey() + " names " + texts.keySet() + ", shows "
                            + shows);
                }
                texts.entrySet().stream()
                        .filter(text -> !List.of(text.getValue().getAsString())
                                .equals(bundle.get(text.getKey().substring("layouts/".length()))))
                        .forEach(text -> wrong.add(layout.name() + ": " + text.getKey() + " is not the bundle's"));
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    void eachFillingFillsExactlyTheDeclaredElementsWithValuesOfTheirKinds() throws IOException {
        final List<String> wrong = new ArrayList<>();
        for (final JsonObject vector : vectors()) {
            final Layout layout = layout(vector);
            final JsonObject filling = vector.getAsJsonObject("filling");
            final JsonObject elements = filling.getAsJsonObject("elements");
            final Set<String> declared =
                    layout.elements().stream().map(Element::id).collect(Collectors.toSet());
            if (!elements.keySet().equals(declared)) {
                wrong.add(layout.name() + " fills " + elements.keySet() + ", declares " + declared);
            }
            values(
                    layout.name(),
                    filling.getAsJsonObject("values"),
                    entry(layout).values().values(),
                    wrong);
            for (final Element element : layout.elements()) {
                final JsonObject filled = elements.getAsJsonObject(element.id());
                final Set<String> expected = switch (element.kind()) {
                    // A button or select filled with nothing is left out this time.
                    case BUTTON -> filled != null && filled.isEmpty() ? Set.of() : Set.of("id");
                    case SELECT -> filled != null && filled.isEmpty() ? Set.of() : Set.of("id", "options");
                    case LIST -> Set.of("items");
                    case LINK -> Set.of();
                    case LANGUAGES -> Set.of("languages");
                    case INPUT -> Set.of("id");
                };
                if (filled == null || !filled.keySet().equals(expected)) {
                    wrong.add(layout.name() + ": " + element.id() + " is filled as " + filled);
                    continue;
                }
                if (element.kind() == Element.Kind.LIST) {
                    filled.getAsJsonArray("items")
                            .forEach(item -> values(
                                    element.id(),
                                    item.getAsJsonObject(),
                                    line(element.keys().getFirst()),
                                    wrong));
                }
                if (element.kind() == Element.Kind.SELECT) {
                    filled.getAsJsonArray("options").forEach(option -> {
                        final JsonObject chosen = option.getAsJsonObject();
                        if (element.keys().size() == 2) {
                            values(
                                    element.id(),
                                    chosen.getAsJsonObject("args"),
                                    line(element.keys().get(1)),
                                    wrong);
                        } else if (!chosen.has("label")) {
                            wrong.add(element.id() + ": an option without a line of its own has no label");
                        }
                    });
                }
            }
        }
        assertEquals(List.of(), wrong);
    }

    /** Each value read as MessageJson reads it, named by the texts it fills and of the kind they name it as. */
    private static void values(
            final String where, final JsonObject json, final Map<String, Kind> named, final List<String> wrong) {
        MessageJson.decodeArgs(Json.decode(json.toString(), Map.class)).forEach((name, value) -> {
            if (Kind.ofValue(value).orElse(null) != named.get(name)) {
                wrong.add(where + ": {" + name + "} is filled as " + json.get(name) + ", named as " + named.get(name));
            }
        });
    }

    private static Map<String, Kind> line(final String key) {
        final LayoutSchema.Found found = LayoutSchema.find(key, SCHEMAS).orElseThrow();
        return found.bundle().declaration(found.entry()).values();
    }

    private static Layout layout(final JsonObject vector) {
        return Json.decode(vector.get("layout").toString(), Layout.class);
    }

    private static LayoutSchema.Entry entry(final Layout layout) {
        return LayoutSchema.describe(layout, SCHEMAS, problem -> {});
    }

    private static List<JsonObject> vectors() throws IOException {
        try (InputStream in = Objects.requireNonNull(
                DiscordLayoutVectorsTest.class.getResourceAsStream("/discord-layouts.json"), "discord-layouts.json")) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonArray()
                    .asList()
                    .stream()
                    .map(JsonElement::getAsJsonObject)
                    .toList();
        }
    }
}
