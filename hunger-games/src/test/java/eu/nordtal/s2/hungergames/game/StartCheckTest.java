package eu.nordtal.s2.hungergames.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.database.inbox.ServerRefusal;
import eu.nordtal.s2.hungergames.db.GameState;
import eu.nordtal.s2.messages.Refusal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Whether the registered game may start, the one decision behind {@code /hg start} and Steward's start button. */
class StartCheckTest {

    private static final int RECOMMENDED = 8;

    private static Optional<String> reason(
            final GameState state, final SeasonPhase phase, final int participants, final boolean confirmed) {
        return StartCheck.refusal(state, phase, participants, RECOMMENDED, confirmed)
                .map(Refusal::reason)
                .map(eu.nordtal.s2.messages.RefusalReason::name);
    }

    @Test
    void noGameRegisteredIsItsOwnRefusal() {
        assertEquals(
                Optional.of(ServerRefusal.NO_GAME.name()),
                StartCheck.refusal(null, SeasonPhase.START_EVENT, 0, RECOMMENDED, false)
                        .map(refusal -> refusal.reason().name()));
    }

    @Test
    void aGameOnlyStartsDuringTheStartEvent() {
        for (final SeasonPhase phase : SeasonPhase.values()) {
            if (phase != SeasonPhase.START_EVENT) {
                assertEquals(
                        Optional.of(ServerRefusal.WRONG_PHASE.name()),
                        reason(GameState.REGISTRATION, phase, 20, true),
                        "a start in " + phase + " was let through");
            }
        }
    }

    @Test
    void aGameThatIsNotInRegistrationNamesTheStateItIsIn() {
        final Optional<Refusal> refusal =
                StartCheck.refusal(GameState.RUNNING, SeasonPhase.START_EVENT, 20, RECOMMENDED, true);

        assertEquals(
                ServerRefusal.WRONG_STATE.name(), refusal.orElseThrow().reason().name());
        assertEquals("RUNNING", refusal.orElseThrow().message().args().get("state"));
    }

    @Test
    void belowTheArithmeticFloorAConfirmationChangesNothing() {
        assertEquals(
                Optional.of(ServerRefusal.BELOW_HARD_MINIMUM.name()),
                reason(GameState.REGISTRATION, SeasonPhase.START_EVENT, 1, true));
    }

    @Test
    void belowTheRecommendedMinimumOnlyAConfirmedStartGoesAhead() {
        assertEquals(
                Optional.of(ServerRefusal.BELOW_SOFT_MINIMUM.name()),
                reason(GameState.REGISTRATION, SeasonPhase.START_EVENT, 4, false));
        assertEquals(Optional.empty(), reason(GameState.REGISTRATION, SeasonPhase.START_EVENT, 4, true));
    }

    @Test
    void atTheRecommendedMinimumItStartsAtOnce() {
        assertEquals(Optional.empty(), reason(GameState.REGISTRATION, SeasonPhase.START_EVENT, RECOMMENDED, false));
    }
}
