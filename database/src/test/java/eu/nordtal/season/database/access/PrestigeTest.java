package eu.nordtal.season.database.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The crest tier as a pure function of play time, at its boundaries. */
class PrestigeTest {

    private static final long HOUR = 3600L;

    private final Prestige prestige = Prestige.defaults();

    @Test
    void aBrandNewPlayerIsTierOne() {
        // The first threshold is 0, so somebody who never played has a crest.
        assertEquals(1, prestige.tierOf(0L));
        assertEquals(1, prestige.tierOf(1L));
        assertEquals(1, prestige.tierOf(-5L), "a negative is impossible by schema CHECK and is not a crash");
    }

    @Test
    void everyThresholdIsInclusiveOnTheSecondItIsReached() {
        // The exact second matters, as the tier is derived on every render.
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
        // Thirteen crest designs exist. The most dedicated player keeps the last one.
        assertEquals(13, prestige.tierOf(5000 * HOUR));
        assertEquals(0L, prestige.secondsToNextTier(5000 * HOUR));
    }

    @Test
    void theDistanceToTheNextTierIsWhatABoardWouldPrint() {
        assertEquals(2 * HOUR, prestige.secondsToNextTier(0L));
        assertEquals(HOUR, prestige.secondsToNextTier(HOUR));
        // At exactly tier 2 (2 h) the next is tier 3 at 5 h, three hours away.
        assertEquals(3 * HOUR, prestige.secondsToNextTier(2 * HOUR));
    }

    @Test
    void aTableThatIsNotThirteenEntriesIsRefused() {
        // Thirteen is a fact about the pack: a fourteenth tier has no code point to render as.
        assertThrows(IllegalArgumentException.class, () -> new Prestige(List.of(0, 2, 5)));
    }

    @Test
    void aTableThatDoesNotStartAtZeroIsRefused() {
        final List<Integer> late = List.of(1, 2, 5, 10, 20, 35, 55, 85, 125, 175, 250, 350, 500);

        assertThrows(IllegalArgumentException.class, () -> new Prestige(late));
    }

    @Test
    void aTableThatDoesNotRiseIsRefused() {
        // A flat pair leaves two tiers unreachable, and the derivation never returns one.
        final List<Integer> flat = List.of(0, 2, 2, 10, 20, 35, 55, 85, 125, 175, 250, 350, 500);

        assertThrows(IllegalArgumentException.class, () -> new Prestige(flat));
    }
}
