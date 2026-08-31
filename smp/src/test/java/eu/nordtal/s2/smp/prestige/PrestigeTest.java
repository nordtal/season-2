package eu.nordtal.s2.smp.prestige;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The crest tier, which is a pure function of one number and is rendered thousands of times a
 * session - in the tab list, on every nametag and in every chat line.
 *
 * <p>The boundaries are what is worth pinning. A player who has just crossed a threshold and does
 * not see the new crest is a bug report; a player who sees it one second early is not, but the
 * off-by-one that produces the second is the same off-by-one that produces the first.
 */
class PrestigeTest {

    private static final long HOUR = 3600L;

    private final Prestige prestige = Prestige.defaults();

    @Test
    void aBrandNewPlayerIsTierOne() {
        // Not tier zero, and not "no crest": the first threshold is 0 precisely so that somebody
        // who has never played has something to draw.
        assertEquals(1, prestige.tierOf(0L));
        assertEquals(1, prestige.tierOf(1L));
        assertEquals(1, prestige.tierOf(-5L), "a negative is impossible by schema CHECK and is not a crash");
    }

    @Test
    void everyThresholdIsInclusiveOnTheSecondItIsReached() {
        // The exact second matters: the tier is derived on every render, so a player watching their
        // own tab list crosses this boundary in front of them.
        for (int tier = 1; tier <= Prestige.TIER_COUNT; tier++) {
            final long at = prestige.secondsFor(tier);

            assertEquals(tier, prestige.tierOf(at), "tier " + tier + " at exactly its threshold");
            if (tier > 1) {
                assertEquals(tier - 1, prestige.tierOf(at - 1), "one second before tier " + tier);
            }
        }
    }

    @Test
    void theProposedTableFromTheConcept() {
        assertEquals(1, prestige.tierOf(HOUR));
        assertEquals(2, prestige.tierOf(2 * HOUR));
        assertEquals(5, prestige.tierOf(20 * HOUR));
        assertEquals(9, prestige.tierOf(130 * HOUR));
        assertEquals(12, prestige.tierOf(400 * HOUR));
        assertEquals(13, prestige.tierOf(500 * HOUR));
    }

    @Test
    void thirteenIsTheTopAndStaysTheTop() {
        // There are thirteen crest designs in the resource pack. A season's most dedicated player
        // does not get a fourteenth, they get the same one for longer.
        assertEquals(13, prestige.tierOf(5000 * HOUR));
        assertEquals(0L, prestige.secondsToNextTier(5000 * HOUR));
    }

    @Test
    void theDistanceToTheNextTierIsWhatABoardWouldPrint() {
        assertEquals(2 * HOUR, prestige.secondsToNextTier(0L));
        assertEquals(HOUR, prestige.secondsToNextTier(HOUR));
        // At exactly tier 2 (2 h), the next is tier 3 at 5 h - three hours away.
        assertEquals(3 * HOUR, prestige.secondsToNextTier(2 * HOUR));
    }

    @Test
    void aTableThatIsNotThirteenEntriesIsRefused() {
        // Thirteen is a fact about the resource pack, not a preference: a fourteenth tier would
        // have no code point to render as.
        assertThrows(IllegalArgumentException.class, () -> new Prestige(List.of(0, 2, 5)));
    }

    @Test
    void aTableThatDoesNotStartAtZeroIsRefused() {
        final List<Integer> late = List.of(1, 2, 5, 10, 20, 35, 55, 85, 125, 175, 250, 350, 500);

        assertThrows(IllegalArgumentException.class, () -> new Prestige(late));
    }

    @Test
    void aTableThatDoesNotRiseIsRefused() {
        // A flat pair would make two tiers unreachable in a way nothing else would notice: the
        // derivation would simply never return one of them.
        final List<Integer> flat = List.of(0, 2, 2, 10, 20, 35, 55, 85, 125, 175, 250, 350, 500);

        assertThrows(IllegalArgumentException.class, () -> new Prestige(flat));
    }
}
