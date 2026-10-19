package eu.nordtal.season.messagerendering;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.Viewer;
import eu.nordtal.season.messages.document.Document;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Documents;
import eu.nordtal.season.messages.document.Slot;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;

class GameDocumentTest {

    record Wheel(long spins) {}

    record Target(String target, boolean active) {}

    record Wanted(String item) {}

    private static final Documents.Packaged MENU = new Documents.Packaged(
            DocumentSpec.menu(
                            "test",
                            "wheel",
                            "Menus",
                            Wheel.class,
                            Slot.item("target", Target.class),
                            Slot.list("wanted", Wanted.class),
                            Slot.label("left"))
                    .declaration(),
            Document.decode("""
                    {"parts": [
                      {"type": "TEXT", "lines": [{"style": "TITLE", "text": {"en": "<accent>Wheel</accent>", "de": "Glücksrad"}}]},
                      {"type": "ITEM", "slot": "target", "lines": [
                        {"when": "active", "text": {"en": "<good>{target}</good> now"}},
                        {"when": "!active", "text": {"en": "{target}"}},
                        {"text": {"en": "<muted>{spins} spins</muted>"}},
                        {"list": "wanted", "text": {"en": "{item} for {target}"}}]},
                      {"type": "LABEL", "slot": "left", "lines": [{"text": {"en": "{spins} left", "de": "{spins} übrig"}}]}]}
                    """));

    record Online(long online, boolean full) {}

    private static final DocumentSpec<Online> FOOTER =
            DocumentSpec.game("test", "footer", "Tab list", DocumentSpec.Shape.TAB_LIST, Online.class);

    private static final Documents.Packaged FOOTER_TEXT =
            new Documents.Packaged(FOOTER.declaration(), Document.decode("""
            {"parts": [
              {"type": "TEXT", "lines": [{"text": {"en": "{online} online"}}, {"when": "full", "text": {"en": "full"}}]},
              {"type": "TEXT", "lines": [{"text": {"en": "<muted>nordtal.eu</muted>"}}]}]}
            """));

    private static final MessageRenderer RENDER = new MessageRenderer(Messages.load("messages/render", Locale.ENGLISH));

    private static GameDocument menu(final Locale locale) {
        return new GameDocument(RENDER, MENU, Viewer.of(locale), new Wheel(3));
    }

    private static String plain(final Component component) {
        final StringBuilder out =
                new StringBuilder(component instanceof final TextComponent text ? text.content() : "");
        component.children().forEach(child -> out.append(plain(child)));
        return out.toString();
    }

    @Test
    void anItemsFirstShownLineIsItsNameAndADataListGivesALinePerItem() {
        final GameDocument.Drawn drawn = menu(Locale.ENGLISH)
                .item(
                        "target",
                        new Target("<b>Spawn", true),
                        Map.of("wanted", List.of(new Wanted("Iron"), new Wanted("Gold"))));

        assertEquals("<b>Spawn now", plain(drawn.name()));
        assertEquals(
                List.of("3 spins", "Iron for <b>Spawn", "Gold for <b>Spawn"),
                drawn.lore().stream().map(GameDocumentTest::plain).toList());
    }

    @Test
    void aLabelIsPlainTextInTheViewersLanguageAndTheTitleFallsBackToEnglish() {
        assertEquals("3 übrig", menu(Locale.GERMAN).label("left"));
        assertEquals("Glücksrad", plain(menu(Locale.GERMAN).title()));
        assertEquals("Wheel", plain(menu(Locale.FRENCH).title()));
    }

    @Test
    void aSentTextShowsTheLinesItsValuesHoldOneUnderTheOther() {
        final MessageRenderer reading = RENDER.reading(spec -> FOOTER_TEXT);

        assertEquals("2 online\nnordtal.eu", plain(reading.format(Locale.ENGLISH, FOOTER.with(new Online(2, false)))));
        assertEquals(
                "2 online\nfull\nnordtal.eu", plain(reading.format(Locale.ENGLISH, FOOTER.with(new Online(2, true)))));
    }
}
