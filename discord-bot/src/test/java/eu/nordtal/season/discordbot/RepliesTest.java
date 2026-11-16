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
                List.of("Your Minecraft account is no longer linked."),
                drawn.components().stream()
                        .map(component -> ((TextDisplay) component).getContent())
                        .toList());
    }

    @Test
    void invitationFailuresNameTheFailedActionInBothLanguages() {
        assertEquals(
                "Your invitation could not be sent.\nTry again in a few minutes.",
                RENDERER.reply(Locale.ENGLISH, Replies.INVITE_FAILED).text());
        assertEquals(
                "Deine Einladung konnte nicht gesendet werden.\nVersuch es in ein paar Minuten noch einmal.",
                RENDERER.reply(Locale.GERMAN, Replies.INVITE_FAILED).text());
        assertEquals(
                "Your response to the invitation could not be confirmed.\nTry again in a few minutes.",
                RENDERER.reply(Locale.ENGLISH, Replies.INVITE_ANSWER_FAILED).text());
        assertEquals(
                "Deine Antwort auf die Einladung konnte nicht bestätigt werden.\nVersuch es in ein paar Minuten noch einmal.",
                RENDERER.reply(Locale.GERMAN, Replies.INVITE_ANSWER_FAILED).text());
    }

    @Test
    void aReplyIsFilledWithItsValues() {
        assertEquals(
                "Your payment link is taking longer than expected.\nSend your reference **NTABC123** to an admin.",
                RENDERER.reply(Locale.ENGLISH, new Sent<>(Replies.PURCHASE_SLOW, new Replies.Reference("NTABC123")))
                        .text());
    }
}
