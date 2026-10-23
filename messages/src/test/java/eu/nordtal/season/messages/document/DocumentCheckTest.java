package eu.nordtal.season.messages.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.messages.text.MessageCheck;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.messages.value.Money;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentCheckTest {

    enum Phase {
        OPEN,
        CLOSED
    }

    record Summary(@Example("30") long days, @Example("300") Money price, boolean donating, Phase phase) {}

    record Tier(@Example("30") long days, @Example("300") Money price) {}

    static final DocumentSpec<Summary> SUMMARY = DocumentSpec.message(
            "test",
            "summary",
            "Purchase",
            Summary.class,
            Slot.button("confirm"),
            Slot.list("tiers", Tier.class),
            Slot.button("extra").optional());

    static final String SOUND = """
            {"parts": [
              {"type": "CONTAINER", "accent": "#4a63d8", "parts": [
                {"type": "TEXT", "lines": [
                  {"text": {"en": "**{days} days** for {price}", "de": ["{days} Tage für {price}", "Für {price}: {days} Tage"]}},
                  {"when": "donating", "text": {"en": "With a donation", "de": "Mit Spende"}},
                  {"when": "phase=open", "text": {"en": "Press {confirm.label}", "de": "Drücke {confirm.label}"}},
                  {"list": "tiers", "text": {"en": "{days} days: {price}", "de": "{days} Tage: {price}"}}]}]},
              {"type": "ROW", "items": [{"slot": "confirm", "label": {"en": "Pay", "de": "Bezahlen"}}]}]}
            """;

    record Wheel(@Example("3") long spins) {}

    record Target(@Example("Harbour") String target, boolean active) {}

    record Wanted(@Example("Diamond") String item) {}

    static final DocumentSpec<Wheel> MENU = DocumentSpec.menu(
            "test",
            "wheel",
            "Menus",
            Wheel.class,
            Slot.item("hub"),
            Slot.item("target", Target.class),
            Slot.list("wanted", Wanted.class),
            Slot.label("left"));

    static final String MENU_SOUND = """
            {"parts": [
              {"type": "TEXT", "lines": [{"style": "TITLE", "text": {"en": "<accent>Wheel</accent>", "de": "Glücksrad"}}]},
              {"type": "ITEM", "slot": "hub", "lines": [
                {"text": {"en": "<emphasis>Spins left: {spins}</emphasis>", "de": "<emphasis>Übrig: {spins}</emphasis>"}},
                {"text": {"en": "<muted>Costs one spin.</muted>", "de": "<muted>Kostet einen Dreh.</muted>"}}]},
              {"type": "ITEM", "slot": "target", "lines": [
                {"when": "active", "text": {"en": "<good>{target}</good>", "de": "<good>{target}</good>"}},
                {"when": "!active", "text": {"en": "{target}", "de": "{target}"}},
                {"list": "wanted", "text": {"en": "<faint>{item} for {target}</faint>", "de": "<faint>{item} für {target}</faint>"}}]},
              {"type": "LABEL", "slot": "left", "lines": [{"text": {"en": "{spins} left", "de": "{spins} übrig"}}]}]}
            """;

    @Test
    void aMenuHoldsItsTitleThenItemsInMiniMessageAndPlainLabels() {
        assertEquals(List.of(), errors(MENU, MENU_SOUND));
    }

    @Test
    void aDataListMayBeDrawnInSeveralItems() {
        assertEquals(
                List.of(),
                errors(
                        MENU,
                        MENU_SOUND.replace(
                                "{\"type\": \"ITEM\", \"slot\": \"hub\", \"lines\": [",
                                "{\"type\": \"ITEM\", \"slot\": \"hub\", \"lines\": [{\"list\": \"wanted\", \"text\":"
                                        + " {\"en\": \"{item}\", \"de\": \"{item}\"}},")));
    }

    @Test
    void aMenuRefusesOtherPartsAndALabelOfTwoLines() {
        assertEquals(
                List.of("a menu holds something other than its title, then items and labels"),
                errors(
                        MENU,
                        MENU_SOUND.replace("{\"type\": \"LABEL\"", "{\"type\": \"SEPARATOR\"}, {\"type\": \"LABEL\"")));
        assertEquals(
                List.of("the label left holds 2 lines, a label takes one"),
                errors(
                        MENU,
                        MENU_SOUND.replace(
                                "[{\"text\": {\"en\": \"{spins} left\"",
                                "[{\"text\": {\"en\": \"x\", \"de\": \"x\"}}, {\"text\": {\"en\": \"{spins} left\"")));
        assertEquals(
                List.of("the label left holds an angle bracket, which the menu's font cannot draw"),
                errors(MENU, MENU_SOUND.replace("{spins} übrig", "<muted>{spins} übrig")));
    }

    @Test
    void anEntryValueIsNamedOnlyInsideItsItem() {
        assertTrue(errors(MENU, MENU_SOUND.replace("{spins} übrig", "{target} übrig")).stream()
                .anyMatch(error -> error.startsWith("the label left in de")));
    }

    private static List<String> errors(final DocumentSpec<?> spec, final String json) {
        return DocumentCheck.errors(spec.declaration(), Document.decode(json), MessageCheck.Mode.PACKAGED);
    }

    @Test
    void aDocumentThatFitsItsDeclarationHasNoProblem() {
        assertEquals(List.of(), errors(SUMMARY, SOUND));
    }

    @Test
    void aRequiredSlotTheDocumentDoesNotPlaceIsMissing() {
        assertEquals(List.of("it does not place the button confirm"), errors(SUMMARY, SOUND.replace("""
                        {"slot": "confirm", "label": {"en": "Pay", "de": "Bezahlen"}}""", """
                        {"link": "https://nordtal.eu", "label": {"en": "Site", "de": "Site"}}""")));
    }

    @Test
    void aSlotPlacedTwiceOrNotDeclaredIsRefused() {
        assertEquals(
                List.of("it places other, which the code does not fill", "it places the button confirm 2 times"),
                errors(SUMMARY, SOUND.replace("""
                        "items": [{"slot": "confirm\"""", """
                        "items": [{"slot": "confirm", "label": {"en": "Again", "de": "Again"}}, {"slot": "other", "label": {"en": "X", "de": "X"}}, {"slot": "confirm\"""")));
    }

    @Test
    void aFileIsOnePackedAddressAndNeverAnHttpsOne() {
        final String file = """
                {"type": "FILE", "media": ["upload://7"]},
              {"type": "ROW\"""";
        assertEquals(List.of(), errors(SUMMARY, SOUND.replace("{\"type\": \"ROW\"", file)));
        assertEquals(
                List.of(
                        "a file holds 2 addresses, Discord takes 1",
                        "a file is at https://nordtal.eu/rules.pdf, but Discord packs only attachment:// or upload:// files"),
                errors(
                        SUMMARY,
                        SOUND.replace(
                                "{\"type\": \"ROW\"",
                                file.replace(
                                        "\"upload://7\"",
                                        "\"https://nordtal.eu/rules.pdf\", \"attachment://rules.pdf\""))));
    }

    @Test
    void aDataListPlacedWhereAButtonBelongsIsRefused() {
        assertTrue(errors(SUMMARY, SOUND.replace("""
                        "slot": "confirm\"""", """
                        "slot": "tiers\""""))
                .contains("it places the data list tiers where a button belongs"));
    }

    @Test
    void aConditionNamesAValueOfTheDocumentAndComparesOnlyAChoice() {
        assertEquals(
                List.of(
                        "the condition missing names {missing}, which the document does not have",
                        "the condition days=3 compares {days}, which is a number, not a choice",
                        "the condition !x=y is not value, !value or value=constant"),
                errors(SUMMARY, SOUND.replace("""
                        "items": [""", """
                        "items": [{"when": "missing", "link": "https://a.eu", "label": {"en": "A", "de": "A"}},
                          {"when": "days=3", "link": "https://b.eu", "label": {"en": "B", "de": "B"}},
                          {"when": "!x=y", "link": "https://c.eu", "label": {"en": "C", "de": "C"}},""")));
    }

    @Test
    void everyTextNeedsEnglishAndPassesTheMessageCheck() {
        final List<String> errors = errors(SUMMARY, SOUND.replace("""
                        {"en": "With a donation", "de": "Mit Spende"}""", """
                        {"de": "Mit Spende {nowhere}"}"""));
        assertEquals(2, errors.size(), errors.toString());
        assertEquals("a text block has no English text, which every other language falls back to", errors.getFirst());
        assertTrue(errors.get(1).startsWith("a text block in de (Mit Spende {nowhere}): "), errors.get(1));
    }

    @Test
    void everyTextCarriesEachLanguageOfTheDocument() {
        assertEquals(
                List.of("a button's label has no text in de, which the document's other texts have"),
                errors(SUMMARY, SOUND.replace("\"Pay\", \"de\": \"Bezahlen\"", "\"Pay\"")));
    }

    @Test
    void aTextKeepsTheTypography() {
        assertEquals(
                List.of("a button's label in en (Pay - now): " + eu.nordtal.season.messages.text.Typography.SEPARATOR),
                errors(SUMMARY, SOUND.replace("\"Pay\"", "\"Pay - now\"")));
    }

    @Test
    void aDeclaredValueNoTextNamesIsRefused() {
        assertEquals(
                List.of("it declares {donating}, which no text names"),
                errors(SUMMARY, SOUND.replace("\"when\": \"donating\", ", "")));
    }

    @Test
    void aButtonLabelLongerThanDiscordDrawsIsRefused() {
        final List<String> errors = errors(
                SUMMARY, SOUND.replace("\"Pay\"", "\"" + "Pay ".repeat(21).strip() + "\""));
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.getFirst().startsWith("a button's label in en"), errors.getFirst());
    }

    @Test
    void aMessageCountsItsComponentsWithEveryConditionalPartShown() {
        final String rows = ",{\"type\": \"SEPARATOR\", \"when\": \"donating\"}".repeat(DocumentCheck.COMPONENTS);
        assertEquals(
                List.of("it draws " + (DocumentCheck.COMPONENTS + 4) + " components, Discord draws "
                        + DocumentCheck.COMPONENTS),
                errors(
                        SUMMARY,
                        SOUND.replace("]}]},\n  {\"type\": \"ROW\"", "]}" + rows + "]},\n  {\"type\": \"ROW\"")));
    }

    @Test
    void theTextOfAMessageFitsInEachLanguage() {
        final String longest = "x".repeat(DocumentCheck.TEXT);
        final List<String> errors = errors(
                SUMMARY,
                SOUND.replace("\"With a donation\"", "\"" + longest + "\"")
                        .replace("\"Mit Spende\"", "\"" + longest + "\""));
        assertTrue(
                errors.stream().anyMatch(error -> error.startsWith("its text blocks in de show ")), errors.toString());
    }

    record Code() {}

    static final DocumentSpec<Code> CODE = DocumentSpec.modal("test", "code", "Link", Code.class, Slot.input("code"));

    @Test
    void aModalHoldsItsTitleAndOneFieldPerRow() {
        final String modal = """
                {"parts": [
                  {"type": "TEXT", "lines": [{"style": "TITLE", "text": {"en": "Link"}}]},
                  {"type": "ROW", "items": [{"slot": "code", "label": {"en": "Code"}, "placeholder": {"en": "The code"}}]}]}
                """;
        assertEquals(List.of(), errors(CODE, modal));
        assertEquals(
                List.of("a modal holds something other than its title and one field per row"),
                errors(CODE, modal.replace("\"style\": \"TITLE\", ", "")));
    }

    @Test
    void aSectionHoldsOneToThreeTextBlocksAndOneAccessory() {
        final DocumentSpec<DocumentSpec.None> spec = DocumentSpec.message("test", "section", "Link");
        assertEquals(
                List.of(
                        "a section holds 0 text blocks, Discord draws 1 to 3",
                        "a section has not either a button or a thumbnail beside it"),
                errors(spec, "{\"parts\": [{\"type\": \"SECTION\", \"parts\": []}]}"));
    }
}
