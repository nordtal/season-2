package eu.nordtal.season.discordbot;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.discordrendering.OneMessage;
import java.util.List;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.junit.jupiter.api.Test;

class DrawnTest {

    @Test
    void theEditSetsComponentsV2EmptiesTheEmbedsAndReplacesTheImages() {
        try (MessageEditData edit =
                new DocumentRenderer.Drawn(List.of(TextDisplay.of("Access")), List.of("link.png"), List.of()).edit()) {
            final DataObject sent = edit.toData();

            assertAll(
                    () -> assertEquals(
                            Message.MessageFlag.IS_COMPONENTS_V2.getValue(),
                            sent.getInt("flags") & Message.MessageFlag.IS_COMPONENTS_V2.getValue()),
                    () -> assertTrue(
                            sent.isNull("content") || sent.getString("content").isEmpty(), sent::toString),
                    () -> assertEquals(0, sent.getArray("embeds").length()),
                    () -> assertEquals(
                            "link.png",
                            sent.getArray("attachments").getObject(0).getString("filename")));
        }
    }

    @Test
    void aNewMessageIsComponentsV2WithoutContent() {
        try (MessageCreateData create =
                new DocumentRenderer.Drawn(List.of(TextDisplay.of("Access")), List.of(), List.of()).create()) {
            final DataObject sent = create.toData();

            assertAll(
                    () -> assertEquals(
                            Message.MessageFlag.IS_COMPONENTS_V2.getValue(),
                            sent.getInt("flags") & Message.MessageFlag.IS_COMPONENTS_V2.getValue()),
                    () -> assertTrue(
                            sent.isNull("content") || sent.getString("content").isEmpty(), sent::toString));
        }
    }

    @Test
    void itsWordsAreItsTextBlocksInsideAndOutsideABox() {
        assertEquals(
                "Title\nLead\nAfter",
                new DocumentRenderer.Drawn(
                                List.of(
                                        Container.of(TextDisplay.of("Title"), TextDisplay.of("Lead")),
                                        TextDisplay.of("After")),
                                List.of(),
                                List.of())
                        .text());
    }

    @Test
    void twoDocumentsMadeOneMessageStandInOrderAndUploadEachImageOnce() {
        final DocumentRenderer.Drawn first = new DocumentRenderer.Drawn(
                List.of(Container.of(TextDisplay.of("Rules"))), List.of("rules.png"), List.of());
        final DocumentRenderer.Drawn second = new DocumentRenderer.Drawn(
                List.of(Container.of(TextDisplay.of("Guide"))), List.of("rules.png", "guide.png"), List.of());

        final DocumentRenderer.Drawn both = OneMessage.of(first, second);

        assertAll(
                () -> assertEquals("Rules\nGuide", both.text()),
                () -> assertEquals(2, both.components().size()),
                () -> assertEquals(List.of("rules.png", "guide.png"), both.attachments()));
    }
}
