package eu.nordtal.season.discordbot;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.season.common.RepositoryRoot;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.layout.LayoutStore;
import eu.nordtal.season.messages.MessageJson;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.Tone;
import eu.nordtal.season.messages.spec.layout.Element;
import eu.nordtal.season.messages.spec.layout.Layout;
import eu.nordtal.season.messages.spec.layout.LayoutSchema;
import eu.nordtal.season.messages.spec.layout.Line;
import eu.nordtal.season.messages.spec.layout.Part;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.junit.jupiter.api.Test;

/** The renderer draws the shared vectors as they expect and keeps every message within Discord's limits. */
class LayoutRendererTest {

    private static final Path VECTORS = Path.of("messages/src/test/resources");

    @Test
    void eachSharedVectorIsDrawnAsItsComponents() throws IOException {
        final Path resources = RepositoryRoot.path().resolve(VECTORS);
        final List<String> wrong = new ArrayList<>();
        try (URLClassLoader loader =
                new URLClassLoader(new URL[] {resources.toUri().toURL()}, null)) {
            final LayoutRenderer renderer = new LayoutRenderer(
                    DiscordRenderer.of(Messages.load(loader, "messages/layouts", Locale.ENGLISH, Locale.GERMAN)),
                    StoredLayoutsStandIn.none());
            final JsonArray vectors = JsonParser.parseString(
                            Files.readString(resources.resolve("discord-layouts.json"), StandardCharsets.UTF_8))
                    .getAsJsonArray();
            for (final JsonElement each : vectors) {
                final JsonObject vector = each.getAsJsonObject();
                final Layout layout = Json.decode(vector.get("layout").toString(), Layout.class);
                final LayoutRenderer.Drawn drawn = renderer.render(
                        layout,
                        filling(layout, vector.getAsJsonObject("filling")),
                        Locales.parse(vector.get("language").getAsString()));
                final JsonElement sent = sent(drawn.components());
                if (!within(vector.get("components"), sent)) {
                    wrong.add(vector.get("name").getAsString() + " is drawn as " + sent);
                }
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    void aDataListTooLongForOneMessageLosesItsLastItemsToACount() {
        final Layout layout = Layout.discord(
                "access",
                "tiers",
                "Contribution",
                List.of(Element.list("tiers", "access/contribution.tier-line")),
                Part.container(Tone.BRAND, Part.text(Line.list("tiers"))));
        final List<Map<String, Object>> items = IntStream.range(0, 400)
                .mapToObj(day -> Map.<String, Object>of(
                        "days", (long) day, "price", eu.nordtal.season.messages.value.Money.euroCents(100)))
                .toList();

        final String text = onlyText(renderer()
                .render(
                        layout,
                        new LayoutRenderer.Filling(Map.of(), Map.of("tiers", new LayoutRenderer.FilledList(items))),
                        Locale.ENGLISH)
                .components());

        assertAll(
                () -> assertTrue(text.length() <= LayoutRenderer.TEXT, text.length() + " characters"),
                () -> assertTrue(text.matches("(?s).*\n\\+\\d+"), text.substring(text.length() - 20)));
    }

    @Test
    void aBlockPerLanguageLosesItsLastLanguagesBeyondFortyComponents() {
        final Layout layout = Layout.discord(
                "access",
                "change",
                "Onboarding",
                List.of(Element.languages("each"), Element.button("change", "access/onboarding.change.button")),
                Part.languages(
                        "each",
                        Part.container(Tone.BRAND, Part.text(Line.title("access/onboarding.change.title"))),
                        Part.row("change")));
        final List<Locale> languages = Collections.nCopies(15, Locale.GERMAN);

        final LayoutRenderer.Drawn drawn = renderer()
                .render(
                        layout,
                        new LayoutRenderer.Filling(
                                Map.of(),
                                Map.of(
                                        "each", new LayoutRenderer.FilledLanguages(languages),
                                        "change", new LayoutRenderer.FilledButton(Ids.ONBOARD))),
                        Locale.ENGLISH);

        // Each language draws a container, its text, a row and its button.
        assertEquals(20, drawn.components().size());
    }

    @Test
    void aStoredRebuildIsDrawnWithItsAddedTextItsLinkButtonAndItsUploadedImage() {
        final Layout declared = Layout.discord(
                "access",
                "change",
                "Onboarding",
                List.of(Element.button("change", "access/onboarding.change.button")),
                Part.text(Line.title("access/onboarding.change.title")),
                Part.row("change"));
        final LayoutRenderer renderer = renderer(StoredLayoutsStandIn.holding(
                List.of(new LayoutStore.StoredLayout(
                        "access",
                        "change",
                        List.of(
                                Part.text(Line.added(Line.Style.HEADING, Map.of("en", "Read first", "de", "Zuerst"))),
                                Part.gallery("upload://7"),
                                Part.row("change", "site")),
                        List.of(Element.link("site", "https://nordtal.eu", Map.of("en", "Site"))),
                        LayoutSchema.hash(declared))),
                List.of(new LayoutStore.LayoutImage(7, "image/png", new byte[] {1}))));

        final LayoutRenderer.Drawn drawn = renderer.render(
                declared,
                new LayoutRenderer.Filling(Map.of(), Map.of("change", new LayoutRenderer.FilledButton(Ids.ONBOARD))),
                Locale.GERMAN);

        final String sent = sent(drawn.components()).toString();
        assertAll(
                () -> assertTrue(sent.contains("\"### Zuerst\""), sent),
                () -> assertTrue(sent.contains("attachment://upload-7.png"), sent),
                () -> assertTrue(sent.contains("https://nordtal.eu") && sent.contains("\"Site\""), sent),
                () -> assertEquals(
                        List.of("upload-7.png"),
                        drawn.uploads().stream().map(FileUpload::getName).toList()));
    }

    private static LayoutRenderer renderer() {
        return renderer(StoredLayoutsStandIn.none());
    }

    private static LayoutRenderer renderer(final StoredLayouts stored) {
        return new LayoutRenderer(
                DiscordRenderer.of(Messages.load("messages/access", Locale.ENGLISH, Locale.GERMAN)), stored);
    }

    private static String onlyText(final List<MessageTopLevelComponent> components) {
        return sent(components)
                .getAsJsonArray()
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("components")
                .get(0)
                .getAsJsonObject()
                .get("content")
                .getAsString();
    }

    /** The components as the bot sends them to Discord. */
    static JsonElement sent(final List<MessageTopLevelComponent> components) {
        try (var data = new MessageCreateBuilder()
                .useComponentsV2()
                .setComponents(components)
                .build()) {
            return JsonParser.parseString(data.toData().toString())
                    .getAsJsonObject()
                    .get("components");
        }
    }

    /** Whether every field {@code expected} names is in {@code actual} with that value. */
    private static boolean within(final JsonElement expected, final JsonElement actual) {
        if (expected.isJsonObject() && actual.isJsonObject()) {
            return expected.getAsJsonObject().entrySet().stream()
                    .allMatch(field -> actual.getAsJsonObject().has(field.getKey())
                            && within(field.getValue(), actual.getAsJsonObject().get(field.getKey())));
        }
        if (expected.isJsonArray() && actual.isJsonArray()) {
            final JsonArray want = expected.getAsJsonArray();
            final JsonArray got = actual.getAsJsonArray();
            return want.size() == got.size()
                    && IntStream.range(0, want.size()).allMatch(i -> within(want.get(i), got.get(i)));
        }
        return expected.equals(actual);
    }

    private static LayoutRenderer.Filling filling(final Layout layout, final JsonObject json) {
        final JsonObject elements = json.getAsJsonObject("elements");
        final Map<String, LayoutRenderer.Filled> filled = new HashMap<>();
        for (final Element element : layout.elements()) {
            if (element.kind() == Element.Kind.LINK) {
                continue;
            }
            final JsonObject one = elements.getAsJsonObject(element.id());
            filled.put(
                    element.id(),
                    switch (element.kind()) {
                        case BUTTON ->
                            new LayoutRenderer.FilledButton(one.get("id").getAsString());
                        case SELECT ->
                            new LayoutRenderer.FilledSelect(
                                    one.get("id").getAsString(),
                                    one.getAsJsonArray("options").asList().stream()
                                            .map(JsonElement::getAsJsonObject)
                                            .map(option -> new LayoutRenderer.Option(
                                                    option.get("value").getAsString(),
                                                    option.has("label")
                                                            ? option.get("label")
                                                                    .getAsString()
                                                            : null,
                                                    option.has("args")
                                                            ? args(option.getAsJsonObject("args"))
                                                            : Map.of(),
                                                    null))
                                            .toList());
                        case LIST ->
                            new LayoutRenderer.FilledList(one.getAsJsonArray("items").asList().stream()
                                    .map(item -> args(item.getAsJsonObject()))
                                    .toList());
                        case LANGUAGES ->
                            new LayoutRenderer.FilledLanguages(one.getAsJsonArray("languages").asList().stream()
                                    .map(tag -> Locales.parse(tag.getAsString()))
                                    .toList());
                        case LINK -> throw new IllegalArgumentException("a link button is filled by nobody");
                        case INPUT -> throw new IllegalArgumentException("a modal's field is in no message");
                    });
        }
        return new LayoutRenderer.Filling(args(json.getAsJsonObject("values")), filled);
    }

    private static Map<String, Object> args(final JsonObject json) {
        return MessageJson.decodeArgs(Json.decode(json.toString(), Map.class));
    }
}
