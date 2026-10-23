package eu.nordtal.season.discordbot;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.nordtal.season.common.RepositoryRoot;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.document.DocumentStore;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.messages.MessageJson;
import eu.nordtal.season.messages.document.Document;
import eu.nordtal.season.messages.document.DocumentCheck;
import eu.nordtal.season.messages.document.DocumentDeclaration;
import eu.nordtal.season.messages.document.Documents;
import eu.nordtal.season.messages.document.Slot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;
import net.dv8tion.jda.api.components.Component;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.selections.SelectMenu;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.junit.jupiter.api.Test;

class DocumentRendererTest {

    private static final Path VECTORS = Path.of("messages/src/test/resources/discord-documents.json");

    @Test
    void eachSharedVectorIsDrawnAsItsComponents() throws IOException {
        final DocumentRenderer renderer = StoredDocumentsStandIn.renderer();
        final List<String> wrong = new ArrayList<>();
        for (final JsonElement each : JsonParser.parseString(
                        Files.readString(RepositoryRoot.path().resolve(VECTORS), StandardCharsets.UTF_8))
                .getAsJsonArray()) {
            final JsonObject vector = each.getAsJsonObject();
            final DocumentDeclaration declaration =
                    Json.decode(vector.get("declaration").toString(), DocumentDeclaration.class);
            final DocumentRenderer.Drawn drawn = renderer.draw(
                    new Documents.Packaged(
                            declaration, Document.decode(vector.get("document").toString())),
                    args(vector.getAsJsonObject("values")),
                    slots(declaration, vector.getAsJsonObject("slots")),
                    Locales.parse(vector.get("language").getAsString()));
            final JsonElement sent = sent(drawn.components());
            if (!within(vector.get("components"), sent)) {
                wrong.add(vector.get("name").getAsString() + " is drawn as " + sent);
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    void aPreviewWritesEachExampleAsTheBotWritesItsValue() {
        final String drawn = StoredDocumentsStandIn.renderer()
                .preview(packaged("contribution"), List.of(Locale.ENGLISH, Locale.GERMAN), Locale.ENGLISH);

        assertTrue(drawn.contains("\\n**€5.00** 30 days\""), drawn);
        assertTrue(drawn.contains("+€5.00 →"), drawn);
    }

    @Test
    void aPreviewDrawsAMessageExampleAsTheTextItsKeyNames() {
        final String drawn = StoredDocumentsStandIn.renderer()
                .preview(packaged("admin-alert"), List.of(Locale.ENGLISH, Locale.GERMAN), Locale.ENGLISH);

        assertTrue(drawn.contains("no build yet"), drawn);
        assertFalse(drawn.contains("admin/run.no-build"), drawn);
    }

    @Test
    void aPreviewDrawsEachMessageOfAListExampleAsTheTextItsKeyNames() {
        final String drawn = StoredDocumentsStandIn.renderer()
                .preview(packaged("admin-run"), List.of(Locale.ENGLISH, Locale.GERMAN), Locale.ENGLISH);

        assertTrue(drawn.contains("no build yet"), drawn);
        assertFalse(drawn.contains("admin/run.no-build"), drawn);
    }

    @Test
    void aPreviewFillsANestedMessageWithItsOwnExamples() {
        final String drawn = StoredDocumentsStandIn.renderer()
                .preview(packaged("admin-run"), List.of(Locale.ENGLISH, Locale.GERMAN), Locale.ENGLISH);

        assertFalse(drawn.contains("?"), drawn);
    }

    @Test
    void everyPackagedDocumentHasAPreviewInItsShape() {
        final DocumentRenderer renderer = StoredDocumentsStandIn.renderer();
        for (final Documents.Packaged each : Documents.packaged(
                        DocumentRendererTest.class.getClassLoader(), StoredDocuments.NAMESPACE)
                .values()) {
            final JsonObject drawn = JsonParser.parseString(
                            renderer.preview(each, List.of(Locale.ENGLISH, Locale.GERMAN), Locale.GERMAN))
                    .getAsJsonObject();
            final String field = switch (each.declaration().shape()) {
                case MESSAGE, MODAL -> "components";
                case PLAIN -> "content";
                case CHANNEL_NAME -> "name";
                case MENU, CHAT, TAB_LIST, KICK_SCREEN, TITLE, BOSS_BAR, SERVER_LIST, ACTION_BAR, HOLOGRAM ->
                    throw new AssertionError("the bot ships no game text");
            };
            assertTrue(drawn.has(field), each.declaration().id() + " is drawn as " + drawn);
        }
    }

    @Test
    void aDraftIsSentAsTheMessageMembersReceiveAndNothingElseIs() {
        final DocumentRenderer renderer = StoredDocumentsStandIn.renderer();
        final List<Locale> languages = List.of(Locale.ENGLISH, Locale.GERMAN);
        for (final Documents.Packaged each : Documents.packaged(
                        DocumentRendererTest.class.getClassLoader(), StoredDocuments.NAMESPACE)
                .values()) {
            switch (each.declaration().shape()) {
                case MESSAGE ->
                    assertFalse(
                            renderer.message(each, languages, Locale.GERMAN)
                                    .getComponents()
                                    .isEmpty(),
                            each.declaration().id());
                case PLAIN ->
                    assertFalse(
                            renderer.message(each, languages, Locale.GERMAN)
                                    .getContent()
                                    .isBlank(),
                            each.declaration().id());
                case MODAL,
                        CHANNEL_NAME,
                        MENU,
                        CHAT,
                        TAB_LIST,
                        KICK_SCREEN,
                        TITLE,
                        BOSS_BAR,
                        SERVER_LIST,
                        ACTION_BAR,
                        HOLOGRAM ->
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> renderer.message(each, languages, Locale.GERMAN),
                            each.declaration().id());
            }
        }
    }

    @Test
    void aSentDraftCarriesItsUploadedImage() {
        final StoredDocuments stored = StoredDocumentsStandIn.holding(
                List.of(), List.of(new DocumentStore.Image(7, "image/png", new byte[] {1})));
        final Documents.Packaged draft =
                new Documents.Packaged(packaged("link-success").declaration(), Document.decode("""
                        {"parts": [
                          {"type": "TEXT", "lines": [{"text": {"en": "Read first", "de": "Zuerst"}}]},
                          {"type": "GALLERY", "media": ["upload://7"]}]}"""));

        try (var sent = StoredDocumentsStandIn.renderer(stored)
                .message(draft, List.of(Locale.ENGLISH, Locale.GERMAN), Locale.GERMAN)) {
            assertTrue(
                    sent.toData().toString().contains("Zuerst"), sent.toData().toString());
            assertEquals(
                    List.of("upload-7.png"),
                    sent.getFiles().stream().map(FileUpload::getName).toList());
        }
    }

    private static Documents.Packaged packaged(final String name) {
        return Documents.packaged(DocumentRendererTest.class.getClassLoader(), StoredDocuments.NAMESPACE)
                .get(name);
    }

    @Test
    void theBuildChecksTheLimitsThisJdaDraws() {
        assertEquals(
                List.of(
                        Message.MAX_COMPONENT_COUNT_IN_COMPONENT_TREE,
                        Message.MAX_CONTENT_LENGTH_COMPONENT_V2,
                        Message.MAX_CONTENT_LENGTH,
                        Button.LABEL_MAX_LENGTH,
                        SelectMenu.PLACEHOLDER_MAX_LENGTH,
                        SelectMenu.OPTIONS_MAX_AMOUNT,
                        SelectOption.LABEL_MAX_LENGTH,
                        Section.MAX_COMPONENTS,
                        MediaGallery.MAX_ITEMS,
                        ActionRow.getMaxAllowed(Component.Type.BUTTON),
                        Modal.MAX_COMPONENTS,
                        Modal.MAX_TITLE_LENGTH,
                        Label.LABEL_MAX_LENGTH,
                        TextInput.MAX_PLACEHOLDER_LENGTH,
                        Channel.MAX_NAME_LENGTH),
                List.of(
                        DocumentCheck.COMPONENTS,
                        DocumentCheck.TEXT,
                        DocumentCheck.PLAIN,
                        DocumentCheck.BUTTON_LABEL,
                        DocumentCheck.SELECT_PLACEHOLDER,
                        DocumentCheck.OPTIONS,
                        DocumentCheck.OPTION_LABEL,
                        DocumentCheck.SECTION_TEXTS,
                        DocumentCheck.GALLERY_ITEMS,
                        DocumentCheck.ROW_BUTTONS,
                        DocumentCheck.MODAL_FIELDS,
                        DocumentCheck.MODAL_TITLE,
                        DocumentCheck.FIELD_LABEL,
                        DocumentCheck.FIELD_PLACEHOLDER,
                        DocumentCheck.CHANNEL_NAME));
    }

    @Test
    void anAdminsDocumentIsDrawnWithItsTextItsLinkButtonItsUploadedImageAndItsFile() {
        final StoredDocuments stored = StoredDocumentsStandIn.holding(
                List.of(new DocumentStore.StoredDocument(
                        StoredDocuments.NAMESPACE,
                        "link-success",
                        Document.decode("""
                                {"parts": [
                                  {"type": "TEXT", "lines": [{"style": "HEADING", "text": {"en": "Read first", "de": "Zuerst"}}]},
                                  {"type": "GALLERY", "media": ["upload://7"]},
                                  {"type": "FILE", "media": ["upload://8"]},
                                  {"type": "ROW", "items": [{"link": "https://nordtal.eu", "label": {"en": "Site", "de": "Seite"}}]}]}"""),
                        StoredDocumentsStandIn.none()
                                .applied(Replies.LINK_SUCCESS)
                                .hash())),
                List.of(
                        new DocumentStore.Image(7, "image/png", new byte[] {1}),
                        new DocumentStore.Image(8, "image/jpeg", new byte[] {2})));

        final DocumentRenderer.Drawn drawn =
                StoredDocumentsStandIn.renderer(stored).reply(Locale.GERMAN, Replies.LINK_SUCCESS);

        final String sent = sent(drawn.components()).toString();
        assertAll(
                () -> assertTrue(sent.contains("\"### Zuerst\""), sent),
                () -> assertTrue(sent.contains("attachment://upload-7.png"), sent),
                () -> assertTrue(
                        sent.contains(
                                "{\"file\":{\"url\":\"attachment://upload-8.jpg\"},\"spoiler\":false,\"type\":13}"),
                        sent),
                () -> assertTrue(sent.contains("https://nordtal.eu") && sent.contains("\"Seite\""), sent),
                () -> assertEquals(
                        List.of("upload-7.png", "upload-8.jpg"),
                        drawn.uploads().stream().map(FileUpload::getName).toList()));
    }

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

    private static Map<String, DocumentRenderer.Filled> slots(
            final DocumentDeclaration declaration, final JsonObject json) {
        final Map<String, DocumentRenderer.Filled> filled = new HashMap<>();
        for (final Slot slot : declaration.slots()) {
            final JsonObject one = json.getAsJsonObject(slot.id());
            if (one == null) {
                continue;
            }
            if (one.isEmpty()) {
                filled.put(slot.id(), new DocumentRenderer.FilledNothing());
                continue;
            }
            filled.put(
                    slot.id(),
                    switch (slot.type()) {
                        case BUTTON ->
                            new DocumentRenderer.FilledButton(one.get("id").getAsString());
                        case SELECT ->
                            new DocumentRenderer.FilledSelect(
                                    one.get("id").getAsString(),
                                    one.getAsJsonArray("options").asList().stream()
                                            .map(JsonElement::getAsJsonObject)
                                            .map(option -> new DocumentRenderer.Option(
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
                            new DocumentRenderer.FilledList(one.getAsJsonArray("items").asList().stream()
                                    .map(item -> args(item.getAsJsonObject()))
                                    .toList());
                        case LANGUAGES ->
                            new DocumentRenderer.FilledLanguages(one.getAsJsonArray("languages").asList().stream()
                                    .map(tag -> Locales.parse(tag.getAsString()))
                                    .toList());
                        case INPUT -> throw new IllegalArgumentException("a modal's field is in no message");
                        case ITEM, LABEL -> throw new IllegalArgumentException("a menu's slot is in no message");
                    });
        }
        return filled;
    }

    private static Map<String, Object> args(final JsonObject json) {
        return MessageJson.decodeArgs(Json.decode(json.toString(), Map.class));
    }
}
