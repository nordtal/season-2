package eu.nordtal.s2.smp.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.Glyphs;
import eu.nordtal.s2.smp.prestige.Prestige;
import eu.nordtal.s2.smp.prestige.PrestigeColours;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/**
 * What a player looks like on the three surfaces they appear on.
 *
 * The composition is the most visible thing in the season - it is in front of every chat line and above every head -
 * and it is also the easiest to get subtly wrong, because the three surfaces show <em>different subsets</em> of the
 * same six elements. The one that matters most is the nametag's omission: aura changes on every death, every hand-in
 * and every duel, and carrying it on a nametag would mean a packet to everyone in range each time.
 *
 * <b>The name's colour</b>
 *
 * {@link #twoDifferentPrestigeTiersAreColouredDifferently} asserts that two identities differing only in play time
 * produce components whose name segment carries a different colour, proving {@code PlayerComposition#name} actually
 * reads the prestige palette rather than a fixed grey.
 */
class PlayerCompositionTest {

    private final PlayerComposition composition =
            new PlayerComposition(Prestige::defaults, () -> PrestigeColours.DEFAULTS);

    /**
     * The ladder is asked for on every render, not captured once.
     *
     * The hours live in {@code prestige.yml} beside the colours now and reload with them, and <b>this supplier is the
     * whole of what makes that true</b>: a {@code PlayerComposition} holding a {@code Prestige} would take the new file
     * and keep the old table.
     */
    @Test
    void theLadderIsReadThroughTheSupplierEveryTime() {
        final java.util.concurrent.atomic.AtomicReference<Prestige> ladder =
                new java.util.concurrent.atomic.AtomicReference<>(Prestige.defaults());
        final PlayerComposition live = new PlayerComposition(ladder::get, () -> PrestigeColours.DEFAULTS);
        // Two hours of play time: tier 2 on the shipped ladder (0, 2, 5, ...).
        final Identity player = new Identity(Locale.GERMAN, false, false, 0, 2 * 3600L);
        final TextColor before = colourOfName(live.chatPrefix("Alice", player), "Alice");

        // The same edit steward makes: tier 3's hour requirement drops, so a player stands a tier higher untouched.
        ladder.set(new Prestige(java.util.List.of(0, 1, 2, 10, 20, 35, 55, 85, 125, 175, 250, 350, 500)));
        final TextColor after = colourOfName(live.chatPrefix("Alice", player), "Alice");

        assertNotNull(before, "the name segment lost its own colour");
        assertNotEquals(
                before,
                after,
                "the hours moved into the reloadable file so that a saved change is visible after"
                        + " /smp reload; a composition that captured the table would still draw"
                        + " tier 2");
    }

    private static String plain(final net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static Identity ordinary() {
        return new Identity(Locale.GERMAN, false, false, 42, 0L);
    }

    /**
     * Finds the colour of whichever child component's own text is exactly {@code name}.
     *
     * {@code PlayerComposition#name} sets it explicitly, so a child that lost track of its own colour would show
     * up here as {@code null} rather than silently inheriting one from a sibling.
     */
    private static TextColor colourOfName(final Component root, final String name) {
        if (root instanceof TextComponent text && name.equals(text.content())) {
            return text.color();
        }
        for (final Component child : root.children()) {
            final TextColor found = colourOfName(child, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    void theTabListCarriesAllSix() {
        final Identity identity = new Identity(Locale.GERMAN, true, true, 42, 0L);
        final String line = plain(composition.tabList("Till", identity));

        assertTrue(line.contains(Glyphs.FLAG_GERMANY), "the flag");
        assertTrue(line.contains("Till"), "the name");
        assertTrue(line.contains(Glyphs.TAG_ADMIN), "the admin letter");
        assertTrue(line.contains(Glyphs.BADGE_DONOR_STAR), "the donor star");
        assertTrue(line.contains(Glyphs.PRESTIGE_CRESTS.get(0)), "the crest");
        assertTrue(line.contains("42"), "the aura");
    }

    /** The one omission that is a performance decision rather than a matter of taste. */
    @Test
    void theNameTagCarriesEverythingExceptTheAura() {
        final String tag = plain(composition.nameTag("Till", ordinary()));

        assertTrue(tag.contains(Glyphs.FLAG_GERMANY));
        assertTrue(tag.contains("Till"));
        assertTrue(tag.contains(Glyphs.PRESTIGE_CRESTS.get(0)));
        assertFalse(
                tag.contains("42"), "aura on a nametag is a packet to everyone in range on every death and hand-in");
    }

    @Test
    void chatCarriesTheFlagTheNameAndTheCrestAndNothingElse() {
        final Identity identity = new Identity(Locale.GERMAN, true, true, 42, 0L);
        final String prefix = plain(composition.chatPrefix("Till", identity));

        assertTrue(prefix.contains(Glyphs.FLAG_GERMANY));
        assertTrue(prefix.contains("Till"));
        assertTrue(prefix.contains(Glyphs.PRESTIGE_CRESTS.get(0)));
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
     * Everybody has a crest from their first minute - {@link Prestige#tierOf} floors at tier one.
     *
     * So the composition never has to reflow around a missing piece.
     */
    @Test
    void everybodyHasACrestAndItRisesWithTime() {
        final String fresh = plain(composition.nameTag("Till", ordinary()));
        assertTrue(fresh.contains(Glyphs.PRESTIGE_CRESTS.get(0)));

        final long manyHours = Prestige.defaults().secondsFor(Prestige.TIER_COUNT);
        final Identity veteran = new Identity(Locale.GERMAN, false, false, 0, manyHours);
        assertTrue(plain(composition.nameTag("Till", veteran))
                .contains(Glyphs.PRESTIGE_CRESTS.get(Prestige.TIER_COUNT - 1)));
    }

    @Test
    void theFlagIsTheWearersLanguageAndFallsBackRatherThanVanishing() {
        assertTrue(plain(composition.chatPrefix("A", new Identity(Locale.ENGLISH, false, false, 0, 0L)))
                .contains(Glyphs.FLAG_UNITED_KINGDOM));
        assertTrue(plain(composition.chatPrefix("A", new Identity(Locale.FRENCH, false, false, 0, 0L)))
                .contains(Glyphs.FLAG_OTHER));
    }

    /** Two identities differing only in play time must not share a name colour. */
    @Test
    void twoDifferentPrestigeTiersAreColouredDifferently() {
        final Identity tierOne = new Identity(Locale.GERMAN, false, false, 0, 0L);
        final Identity tierThirteen =
                new Identity(Locale.GERMAN, false, false, 0, Prestige.defaults().secondsFor(Prestige.TIER_COUNT));

        final TextColor colourOne = colourOfName(composition.chatPrefix("Alice", tierOne), "Alice");
        final TextColor colourThirteen = colourOfName(composition.chatPrefix("Bob", tierThirteen), "Bob");

        assertNotNull(colourOne, "the name segment lost its own colour");
        assertNotNull(colourThirteen, "the name segment lost its own colour");
        assertNotEquals(
                colourOne,
                colourThirteen,
                "tier 1 and tier 13 read as the same colour, so a player cannot tell prestige apart"
                        + " by looking at a name");
    }

    /**
     * Every tier gets the hex {@code prestige.yml} declares for it, on every surface the name is drawn on.
     *
     * The "everywhere" that is asked for follows from all three calling the same {@code name} method, and this
     * pins that down for each surface individually.
     */
    @Test
    void everySurfacePaintsTheSameTierTheSameColour() {
        final Identity tierFive =
                new Identity(Locale.GERMAN, false, false, 0, Prestige.defaults().secondsFor(5));
        final TextColor expected = PrestigeColours.DEFAULTS.tier(5);

        assertEquals(expected, colourOfName(composition.chatPrefix("Cara", tierFive), "Cara"));
        assertEquals(expected, colourOfName(composition.nameTag("Cara", tierFive), "Cara"));
        assertEquals(expected, colourOfName(composition.tabList("Cara", tierFive), "Cara"));
    }

    /**
     * The admin colour wins over the tier, on every surface.
     *
     * It is not a fourteenth tier, so an admin at tier 13 must not show tier 13's colour just because it is the
     * highest.
     */
    @Test
    void theAdminColourWinsOverTheProminentTier() {
        final Identity adminAtTopTier =
                new Identity(Locale.GERMAN, true, false, 0, Prestige.defaults().secondsFor(Prestige.TIER_COUNT));

        final TextColor colour = colourOfName(composition.tabList("Root", adminAtTopTier), "Root");

        assertEquals(PrestigeColours.DEFAULTS.admin(), colour);
        assertNotEquals(
                PrestigeColours.DEFAULTS.tier(Prestige.TIER_COUNT),
                colour,
                "an admin at the top tier still showed the tier's own colour, so the admin override"
                        + " is being treated as if it were a fourteenth tier rather than winning"
                        + " over all thirteen");
    }
}
