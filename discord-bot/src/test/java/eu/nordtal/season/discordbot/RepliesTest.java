package eu.nordtal.season.discordbot;

import static eu.nordtal.season.discordbot.AccessMessages.MESSAGES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.nordtal.season.messages.Messages;
import java.util.List;
import java.util.Locale;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import org.junit.jupiter.api.Test;

/** A reply to one text is one text block filled with the values of the text. */
class RepliesTest {

    private static final LayoutRenderer RENDERER = new LayoutRenderer(
            DiscordRenderer.of(Messages.load("messages/access", Locale.ENGLISH, Locale.GERMAN)),
            StoredLayoutsStandIn.none());

    @Test
    void aReplyIsItsTextAsOneTextBlock() {
        final LayoutRenderer.Drawn drawn =
                RENDERER.reply(Locale.ENGLISH, MESSAGES.unlink().success());

        assertEquals(
                List.of("Your Minecraft account has been unlinked."),
                drawn.components().stream()
                        .map(component -> ((TextDisplay) component).getContent())
                        .toList());
    }

    @Test
    void aReplyIsFilledWithItsTextsValues() {
        assertEquals(
                "The payment link is still not here. Your reference is **NTABC123**.",
                RENDERER.reply(Locale.ENGLISH, MESSAGES.purchase().linkSection().slow("NTABC123"))
                        .text()
                        .substring(0, 67));
    }

    @Test
    void aTextNoReplyShowsIsRefused() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RENDERER.reply(Locale.ENGLISH, MESSAGES.link().title()));
    }
}
