package eu.nordtal.s2.smp.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.PlayerId;
import eu.nordtal.s2.database.access.PlayerIdentity;
import eu.nordtal.s2.packrendering.Glyphs;
import eu.nordtal.s2.smp.prestige.Prestige;
import eu.nordtal.s2.smp.prestige.PrestigeColours;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/**
 * What a player looks like on the three surfaces they appear on.
 *
 * Each surface shows a different subset; the nametag leaves aura out, since it would cost a packet per change.
 */
class PlayerCompositionTest {

    private final PlayerComposition composition =
            new PlayerComposition(Prestige::defaults, () -> PrestigeColours.DEFAULTS);

    /** The ladder is asked for on every render, not captured, so a reloaded {@code prestige.yml} takes effect. */
    @Test
    void theLadderIsReadThroughTheSupplierEveryTime() {
        final java.util.concurrent.atomic.AtomicReference<Prestige> ladder =
                new java.util.concurrent.atomic.AtomicReference<>(Prestige.defaults());
        final PlayerComposition live = new PlayerComposition(ladder::get, () -> PrestigeColours.DEFAULTS);
        // Two hours of play time: tier 2 on the shipped ladder (0, 2, 5, ...).
        final PlayerIdentity player = identity(Locale.GERMAN, false, false, 0, 2 * 3600L);
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

    private static PlayerIdentity ordinary() {
        return identity(Locale.GERMAN, false, false, 42, 0L);
    }

    /** Finds the colour set on the child whose own text is exactly {@code name}, null if it set none. */
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
        final PlayerIdentity identity = identity(Locale.GERMAN, true, true, 42, 0L);
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
        final PlayerIdentity identity = identity(Locale.GERMAN, true, true, 42, 0L);
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

    /** Everybody has a crest from their first minute: {@link Prestige#tierOf} floors at tier one. */
    @Test
    void everybodyHasACrestAndItRisesWithTime() {
        final String fresh = plain(composition.nameTag("Till", ordinary()));
        assertTrue(fresh.contains(Glyphs.PRESTIGE_CRESTS.get(0)));

        final long manyHours = Prestige.defaults().secondsFor(Prestige.TIER_COUNT);
        final PlayerIdentity veteran = identity(Locale.GERMAN, false, false, 0, manyHours);
        assertTrue(plain(composition.nameTag("Till", veteran))
                .contains(Glyphs.PRESTIGE_CRESTS.get(Prestige.TIER_COUNT - 1)));
    }

    @Test
    void theFlagIsTheWearersLanguageAndFallsBackRatherThanVanishing() {
        assertTrue(plain(composition.chatPrefix("A", identity(Locale.ENGLISH, false, false, 0, 0L)))
                .contains(Glyphs.FLAG_UNITED_KINGDOM));
        assertTrue(plain(composition.chatPrefix("A", identity(Locale.FRENCH, false, false, 0, 0L)))
                .contains(Glyphs.FLAG_OTHER));
    }

    /** Two identities differing only in play time must not share a name colour. */
    @Test
    void twoDifferentPrestigeTiersAreColouredDifferently() {
        final PlayerIdentity tierOne = identity(Locale.GERMAN, false, false, 0, 0L);
        final PlayerIdentity tierThirteen =
                identity(Locale.GERMAN, false, false, 0, Prestige.defaults().secondsFor(Prestige.TIER_COUNT));

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

    /** Every tier gets the hex {@code prestige.yml} declares for it, on every surface the name is drawn on. */
    @Test
    void everySurfacePaintsTheSameTierTheSameColour() {
        final PlayerIdentity tierFive =
                identity(Locale.GERMAN, false, false, 0, Prestige.defaults().secondsFor(5));
        final TextColor expected = PrestigeColours.DEFAULTS.tier(5);

        assertEquals(expected, colourOfName(composition.chatPrefix("Cara", tierFive), "Cara"));
        assertEquals(expected, colourOfName(composition.nameTag("Cara", tierFive), "Cara"));
        assertEquals(expected, colourOfName(composition.tabList("Cara", tierFive), "Cara"));
    }

    /** The admin colour wins over the tier on every surface, even at tier 13. */
    @Test
    void theAdminColourWinsOverTheProminentTier() {
        final PlayerIdentity adminAtTopTier =
                identity(Locale.GERMAN, true, false, 0, Prestige.defaults().secondsFor(Prestige.TIER_COUNT));

        final TextColor colour = colourOfName(composition.tabList("Root", adminAtTopTier), "Root");

        assertEquals(PrestigeColours.DEFAULTS.admin(), colour);
        assertNotEquals(
                PrestigeColours.DEFAULTS.tier(Prestige.TIER_COUNT),
                colour,
                "an admin at the top tier still showed the tier's own colour, so the admin override"
                        + " is being treated as if it were a fourteenth tier rather than winning"
                        + " over all thirteen");
    }

    private static PlayerIdentity identity(
            final Locale language,
            final boolean admin,
            final boolean donor,
            final int aura,
            final long playtimeSeconds) {
        return new PlayerIdentity(
                PlayerId.of(UUID.randomUUID()), null, null, language, null, admin, donor, aura, playtimeSeconds);
    }
}
