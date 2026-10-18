package eu.nordtal.season.discordbot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.messages.document.Sent;
import java.util.List;
import java.util.Locale;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import org.junit.jupiter.api.Test;

/** A reply is its document: here one text block. */
class RepliesTest {

    private static final DocumentRenderer RENDERER = StoredDocumentsStandIn.renderer();

    @Test
    void aReplyIsItsTextAsOneTextBlock() {
        final DocumentRenderer.Drawn drawn = RENDERER.reply(Locale.ENGLISH, Replies.UNLINK_SUCCESS);

        assertEquals(
                List.of("Your Minecraft account is unlinked."),
                drawn.components().stream()
                        .map(component -> ((TextDisplay) component).getContent())
                        .toList());
    }

    @Test
    void aReplyIsFilledWithItsValues() {
        assertEquals(
                "The payment link is still missing. Your reference is **NTABC123**.",
                RENDERER.reply(Locale.ENGLISH, new Sent<>(Replies.PURCHASE_SLOW, new Replies.Reference("NTABC123")))
                        .text()
                        .substring(0, 66));
    }
}
