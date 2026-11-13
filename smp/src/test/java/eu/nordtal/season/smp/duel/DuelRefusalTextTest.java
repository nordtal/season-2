package eu.nordtal.season.smp.duel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.document.AppliedDocuments;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.document.Sent;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** A refused duel names why: what holds the arena or the negotiation area. */
class DuelRefusalTextTest {

    private static final MessageRenderer RENDERER = MessageRenderer.of(
                    Messages.load("messages/database", Locale.ENGLISH, Locale.GERMAN))
            .reading(new AppliedDocuments(DuelRefusalTextTest.class.getClassLoader(), Set.of("smp")));

    private static String english(final Sent<?> sent) {
        return PlainTextComponentSerializer.plainText().serialize(RENDERER.format(Locale.ENGLISH, sent));
    }

    @Test
    void aBusyArenaAndAnArenaNotReadyAreToldApart() {
        final String busy =
                english(Negotiation.UNAVAILABLE.with(new Negotiation.Unavailable(Duels.Blocked.ARENA_BUSY)));
        final String notReady =
                english(Negotiation.UNAVAILABLE.with(new Negotiation.Unavailable(Duels.Blocked.ARENA_NOT_READY)));
        assertEquals(false, busy.isBlank(), "the busy arena renders nothing");
        assertEquals(false, notReady.isBlank(), "the arena that is not ready renders nothing");
        assertEquals(false, busy.equals(notReady), "both reasons read " + busy);
    }

    @Test
    void theHeldAreaSaysWhetherADuelRunsOrKitsArePicked() {
        assertEquals(
                "The negotiation area is taken: a duel is under way.",
                english(Negotiation.AREA_HELD.with(new Negotiation.Held(true))));
        assertEquals(
                "The negotiation area is taken: two players are picking kits.",
                english(Negotiation.AREA_HELD.with(new Negotiation.Held(false))));
    }
}
