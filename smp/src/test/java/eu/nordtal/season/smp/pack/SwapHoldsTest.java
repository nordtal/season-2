package eu.nordtal.season.smp.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import java.util.UUID;
import org.bukkit.event.player.PlayerResourcePackStatusEvent.Status;
import org.junit.jupiter.api.Test;

/** A live swap holds each player until their client is done with its pack, and is done when nobody is held. */
class SwapHoldsTest {

    private static final UUID ANNA = new UUID(0, 1);
    private static final UUID BEN = new UUID(0, 2);
    private static final UUID PACK = new UUID(9, 9);

    private final SwapHolds holds = new SwapHolds();

    @Test
    void aPlayerIsHeldUntilTheirPackHasLoaded() {
        holds.hold(ANNA, 1, PACK, 0);

        assertTrue(holds.answer(ANNA, PACK, Status.ACCEPTED, 1).isEmpty());
        assertTrue(holds.answer(ANNA, PACK, Status.DOWNLOADED, 2).isEmpty());
        assertTrue(holds.holds(ANNA));

        final SwapHolds.Answered answered = holds.answer(ANNA, PACK, Status.SUCCESSFULLY_LOADED, 9_000_000_000L)
                .orElseThrow();
        assertEquals(new SwapHolds.Answered(1, 9000, true), answered);
        assertFalse(holds.holds(ANNA));
    }

    @Test
    void theSwapIsDoneOnlyWhenItsLastPlayerAnswers() {
        holds.hold(ANNA, 1, PACK, 0);
        holds.hold(BEN, 1, PACK, 0);

        assertFalse(holds.answer(ANNA, PACK, Status.DECLINED, 1).orElseThrow().last());
        assertTrue(
                holds.answer(BEN, PACK, Status.FAILED_DOWNLOAD, 1).orElseThrow().last());
    }

    @Test
    void anAnswerForAnotherPackKeepsThePlayerHeld() {
        holds.hold(ANNA, 1, PACK, 0);

        assertTrue(holds.answer(ANNA, new UUID(8, 8), Status.SUCCESSFULLY_LOADED, 1)
                .isEmpty());
        assertTrue(holds.holds(ANNA));
    }

    @Test
    void theLastPlayerLeavingEndsTheSwap() {
        holds.hold(ANNA, 1, PACK, 0);
        holds.hold(BEN, 1, PACK, 0);

        assertEquals(OptionalLong.empty(), holds.leave(ANNA));
        assertEquals(OptionalLong.of(1), holds.leave(BEN));
        assertEquals(OptionalLong.empty(), holds.leave(BEN));
    }

    @Test
    void theHoldRunningOutLetsGoOfThatSwapsPlayersOnly() {
        holds.hold(ANNA, 1, PACK, 0);
        holds.hold(BEN, 2, PACK, 0);

        assertEquals(1, holds.release(1));
        assertFalse(holds.holds(ANNA));
        assertTrue(holds.holds(BEN));
        assertEquals(0, holds.release(1));
    }

    @Test
    void aNewerSwapForTheSamePlayerEndsTheOlderOneWhenItWasTheirsAlone() {
        holds.hold(ANNA, 1, PACK, 0);

        assertEquals(OptionalLong.of(1), holds.hold(ANNA, 2, PACK, 5));
        assertEquals(OptionalLong.of(2), holds.leave(ANNA));
    }
}
