package eu.nordtal.season.database.access;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.value.DisplayName;
import eu.nordtal.season.messages.value.Glyph;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A player's card: what they are on the network and the crest their play earned, in the reader's language. */
class PlayerCardTest {

    private static final Messages BUNDLE =
            Messages.load(PlayerCardTest.class.getClassLoader(), "messages/database", Locale.ENGLISH, Locale.GERMAN);

    private static final DisplayName ALEX = new DisplayName(PlayerId.of(new UUID(0L, 10L)), "Alex");

    private static final long TWELVE_HOURS_FIVE_MINUTES = 12 * 3600L + 5 * 60L;

    @Test
    void anAdminWhoDonatedIsShownAsTheAdmin() {
        assertEquals(PlayerCard.Role.ADMIN, role(true, true));
        assertEquals(PlayerCard.Role.DONOR, role(false, true));
        assertEquals(PlayerCard.Role.PLAYER, role(false, false));
    }

    @Test
    void theCrestIsTheOneThePlayEarned() {
        // Ten hours reach tier 4 by default and twenty tier 5, so twelve hours wear the fourth crest.
        final MessageRef card = PlayerCard.of(ALEX, false, false, TWELVE_HOURS_FIVE_MINUTES, Prestige.defaults());

        assertEquals(4, card.args().get("tier"));
        assertEquals(new Glyph("crest-4"), card.args().get("crest"));
    }

    @Test
    void theCardReadsInTheReadersLanguage() {
        final MessageRef card = PlayerCard.of(ALEX, true, false, TWELVE_HOURS_FIVE_MINUTES, Prestige.defaults());

        // Plain text leaves the admin tag and the crest out, which the game draws as glyphs.
        assertEquals("Alex \n Prestige 4\nPlayed for 12 hours and 5 minutes", BUNDLE.format(Locale.ENGLISH, card));
        assertEquals("Alex \n Prestige 4\n12 Stunden und 5 Minuten gespielt", BUNDLE.format(Locale.GERMAN, card));
    }

    private static Object role(final boolean admin, final boolean donor) {
        return PlayerCard.of(ALEX, admin, donor, 0L, Prestige.defaults()).args().get("role");
    }
}
