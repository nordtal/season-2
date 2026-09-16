package eu.nordtal.s2.smp.prestige;

import net.kyori.adventure.text.format.TextColor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * season-2-ingame/23: thirteen prestige colours plus the one that overrides them all, a bad hex
 * value has to fall back to the default rather than vanish, and the admin colour must never equal a
 * prestige tier's own default - {@code PlayerCompositionTest} already proves the admin override wins
 * on a real composition, this file is the palette on its own.
 */
class PrestigeColoursTest {

    private static List<String> defaultTierHexes() {
        final List<String> hexes = new ArrayList<>();
        for (int tier = 1; tier <= Prestige.TIER_COUNT; tier++) {
            hexes.add(PrestigeColours.DEFAULTS.tier(tier).asHexString());
        }
        return hexes;
    }

    @Test
    @DisplayName("all thirteen tiers parse to a colour, even from an empty declaration")
    void everyTierHasADefault() {
        final List<String> problems = new ArrayList<>();
        final List<String> blanks = new ArrayList<>();
        for (int tier = 1; tier <= Prestige.TIER_COUNT; tier++) {
            blanks.add("");
        }

        final PrestigeColours colours = PrestigeColours.parse(blanks, "", problems::add);

        for (int tier = 1; tier <= Prestige.TIER_COUNT; tier++) {
            assertNotEquals(null, colours.tier(tier));
        }
        assertNotEquals(null, colours.admin());
        assertEquals(List.of(), problems,
                "a blank declaration is what a freshly written file with a tone not yet filled in"
                        + " looks like, and it must not be reported");
    }

    @Test
    @DisplayName("an invalid hex value falls back to the default and is reported")
    void invalidHexFallsBackAndReports() {
        final List<String> declared = new ArrayList<>(defaultTierHexes());
        declared.set(7, "not-a-colour"); // tier 8

        final List<String> problems = new ArrayList<>();
        final PrestigeColours colours = PrestigeColours.parse(declared, "#ff5555", problems::add);

        assertEquals(PrestigeColours.DEFAULTS.tier(8), colours.tier(8),
                "a colour that cannot be parsed must fall back to the default rather than leaving the"
                        + " name uncoloured - an unpainted name is invisible in exactly the way this"
                        + " ticket exists to fix");
        assertEquals(1, problems.size(), "the bad value has to be reported exactly once");
        assertTrue(problems.get(0).contains("tier 8") && problems.get(0).contains("not-a-colour"),
                "the report has to name which tier and which value were rejected, or nobody reading"
                        + " the log can find the line to fix: " + problems);
    }

    @Test
    @DisplayName("an invalid admin hex value falls back to the default and is reported")
    void invalidAdminHexFallsBackAndReports() {
        final List<String> problems = new ArrayList<>();
        final PrestigeColours colours =
                PrestigeColours.parse(defaultTierHexes(), "also-not-a-colour", problems::add);

        assertEquals(PrestigeColours.DEFAULTS.admin(), colours.admin());
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("admin") && problems.get(0).contains("also-not-a-colour"));
    }

    @Test
    @DisplayName("a blank hex value falls back to the default without a complaint")
    void blankHexFallsBackSilently() {
        final List<String> declared = new ArrayList<>(defaultTierHexes());
        declared.set(0, "");
        final List<String> problems = new ArrayList<>();

        final PrestigeColours colours = PrestigeColours.parse(declared, "#ff5555", problems::add);

        assertEquals(PrestigeColours.DEFAULTS.tier(1), colours.tier(1));
        assertEquals(List.of(), problems);
    }

    @Test
    @DisplayName("a table that is not exactly thirteen entries is refused")
    void wrongCountIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> PrestigeColours.parse(List.of("#5fbfae", "#5ea9d6"), "#ff5555", problem -> { }));
    }

    @Test
    @DisplayName("no two default tiers are the same colour")
    void allThirteenDefaultsDiffer() {
        for (int a = 1; a <= Prestige.TIER_COUNT; a++) {
            for (int b = a + 1; b <= Prestige.TIER_COUNT; b++) {
                assertNotEquals(PrestigeColours.DEFAULTS.tier(a), PrestigeColours.DEFAULTS.tier(b),
                        "tier " + a + " and tier " + b + " default to the same colour");
            }
        }
    }

    @Test
    @DisplayName("the admin default is not any prestige tier's default")
    void adminDefaultIsNotATierDefault() {
        final TextColor admin = PrestigeColours.DEFAULTS.admin();
        for (int tier = 1; tier <= Prestige.TIER_COUNT; tier++) {
            assertNotEquals(admin, PrestigeColours.DEFAULTS.tier(tier),
                    "the admin colour must never equal a prestige tier's default, or an admin's"
                            + " colour would be mistaken for a real tier");
        }
    }

    @Test
    @DisplayName("a tier outside 1..13 is refused")
    void outOfRangeTierIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> PrestigeColours.DEFAULTS.tier(0));
        assertThrows(IllegalArgumentException.class,
                () -> PrestigeColours.DEFAULTS.tier(Prestige.TIER_COUNT + 1));
    }
}
