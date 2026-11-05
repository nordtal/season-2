package eu.nordtal.season.hungergames.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.messages.Refusal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Whether the registered game may start, the one decision behind {@code /hg start} and Steward's start button. */
class StartCheckTest {

    private static final int RECOMMENDED = 8;

    private static Optional<String> reason(final int participants, final boolean confirmed) {
        return StartCheck.refusal(null, true, participants, RECOMMENDED, confirmed)
                .map(Refusal::reason)
                .map(eu.nordtal.season.messages.RefusalReason::name);
    }

    @Test
    void noGameRegisteredIsItsOwnRefusal() {
        assertEquals(
                Optional.of(ServerRefusal.NO_GAME.name()),
                StartCheck.refusal(null, false, 0, RECOMMENDED, false)
                        .map(refusal -> refusal.reason().name()));
    }

    @Test
    void aRegisteredRoundWithEnoughParticipantsStartsOnItsOwnChecksAlone() {
        assertEquals(Optional.empty(), reason(20, false));
    }

    @Test
    void aGameUnderWayNamesTheStateItIsIn() {
        final Optional<Refusal> refusal = StartCheck.refusal(HgGameState.RUNNING, false, 20, RECOMMENDED, true);

        assertEquals(
                ServerRefusal.WRONG_STATE.name(), refusal.orElseThrow().reason().name());
        assertEquals("RUNNING", refusal.orElseThrow().message().args().get("state"));
    }

    @Test
    void belowTheArithmeticFloorAConfirmationChangesNothing() {
        assertEquals(Optional.of(ServerRefusal.BELOW_HARD_MINIMUM.name()), reason(1, true));
    }

    @Test
    void belowTheRecommendedMinimumOnlyAConfirmedStartGoesAhead() {
        assertEquals(Optional.of(ServerRefusal.BELOW_SOFT_MINIMUM.name()), reason(4, false));
        assertEquals(Optional.empty(), reason(4, true));
    }

    @Test
    void atTheRecommendedMinimumItStartsAtOnce() {
        assertEquals(Optional.empty(), reason(RECOMMENDED, false));
    }
}
