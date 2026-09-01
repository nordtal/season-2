package eu.nordtal.s2.smp.player;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.smp.prestige.Prestige;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a player looks like on the three surfaces they appear on.
 *
 * <p>The composition is the most visible thing in the season - it is in front of every chat line and
 * above every head - and it is also the easiest to get subtly wrong, because the three surfaces show
 * <em>different subsets</em> of the same six elements. The one that matters most is the nametag's
 * omission: aura changes on every death, every hand-in and every duel, and carrying it on a nametag
 * would mean a packet to everyone in range each time.
 */
class PlayerCompositionTest {

    private final PlayerComposition composition = new PlayerComposition(Prestige.defaults());

    private static String plain(final net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static Identity ordinary() {
        return new Identity(Locale.GERMAN, false, false, 42, 0L);
    }

    @Test
    void theTabListCarriesAllSix() {
        final Identity identity = new Identity(Locale.GERMAN, true, true, 42, 0L);
        final String line = plain(composition.tabList("Till", identity));

        assertTrue(line.contains(Glyphs.FLAG_GERMANY), "the flag");
        assertTrue(line.contains("Till"), "the name");
        assertTrue(line.contains(Glyphs.TAG_ADMIN), "the admin letter");
        assertTrue(line.contains(Glyphs.BADGE_DONOR_STAR), "the donor star");
        assertTrue(line.contains(Glyphs.PRESTIGE_CRESTS[0]), "the crest");
        assertTrue(line.contains("42"), "the aura");
    }

    /** The one omission that is a performance decision rather than a matter of taste. */
    @Test
    void theNameTagCarriesEverythingExceptTheAura() {
        final String tag = plain(composition.nameTag("Till", ordinary()));

        assertTrue(tag.contains(Glyphs.FLAG_GERMANY));
        assertTrue(tag.contains("Till"));
        assertTrue(tag.contains(Glyphs.PRESTIGE_CRESTS[0]));
        assertFalse(tag.contains("42"),
                "aura on a nametag is a packet to everyone in range on every death and hand-in");
    }

    @Test
    void chatCarriesTheFlagTheNameAndTheCrestAndNothingElse() {
        final Identity identity = new Identity(Locale.GERMAN, true, true, 42, 0L);
        final String prefix = plain(composition.chatPrefix("Till", identity));

        assertTrue(prefix.contains(Glyphs.FLAG_GERMANY));
        assertTrue(prefix.contains("Till"));
        assertTrue(prefix.contains(Glyphs.PRESTIGE_CRESTS[0]));
        assertFalse(prefix.contains(Glyphs.TAG_ADMIN), "chat is not where authority is announced");
        assertFalse(prefix.contains("42"));
    }

    @Test
    void theBadgesAppearOnlyWhenTheyAreEarned() {
        final String plain = plain(composition.tabList("Till", ordinary()));

        assertFalse(plain.contains(Glyphs.TAG_ADMIN));
        assertFalse(plain.contains(Glyphs.BADGE_DONOR_STAR));
    }

    /**
     * Everybody has a crest from their first minute - {@link Prestige#tierOf} floors at tier one -
     * so the composition never has to reflow around a missing piece.
     */
    @Test
    void everybodyHasACrestAndItRisesWithTime() {
        final String fresh = plain(composition.nameTag("Till", ordinary()));
        assertTrue(fresh.contains(Glyphs.PRESTIGE_CRESTS[0]));

        final long manyHours = Prestige.defaults().secondsFor(Prestige.TIER_COUNT);
        final Identity veteran = new Identity(Locale.GERMAN, false, false, 0, manyHours);
        assertTrue(plain(composition.nameTag("Till", veteran))
                .contains(Glyphs.PRESTIGE_CRESTS[Prestige.TIER_COUNT - 1]));
    }

    @Test
    void theFlagIsTheWearersLanguageAndFallsBackRatherThanVanishing() {
        assertTrue(plain(composition.chatPrefix("A", new Identity(Locale.ENGLISH, false, false, 0, 0L)))
                .contains(Glyphs.FLAG_UNITED_KINGDOM));
        assertTrue(plain(composition.chatPrefix("A", new Identity(Locale.FRENCH, false, false, 0, 0L)))
                .contains(Glyphs.FLAG_OTHER));
    }
}
