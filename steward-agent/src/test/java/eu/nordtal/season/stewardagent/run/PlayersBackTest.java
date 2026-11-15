package eu.nordtal.season.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.stewardagent.run.PlayersBack.Verdict;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** A late player is waited for, a lost one is warned about, and nothing holds a run forever. */
class PlayersBackTest {

    private static final Duration PAST_CAP = PlayersBack.CAP.plusSeconds(1);

    private static final Duration STALLED_NOT = Duration.ofSeconds(1);

    @Test
    void everyPlayerBackEndsTheCountAtOnce() {
        assertEquals(Verdict.DONE, PlayersBack.judge(0, 0, false, Duration.ZERO, STALLED_NOT));
    }

    @Test
    void beforeTheCapTheCountWaitsWhateverTheWaitingRoomsSay() {
        assertEquals(Verdict.WAIT, PlayersBack.judge(2, 0, false, Duration.ofSeconds(29), STALLED_NOT));
    }

    @Test
    void pastTheCapAPlayerStillWaitingIsLateAndNotLost() {
        assertEquals(Verdict.WAIT, PlayersBack.judge(3, 1, false, PAST_CAP, STALLED_NOT));
        assertEquals(Verdict.WAIT, PlayersBack.judge(3, 1, false, Duration.ofHours(1), STALLED_NOT));
    }

    @Test
    void pastTheCapNobodyLeftWaitingMeansThePlayersAreLost() {
        assertEquals(Verdict.LOST, PlayersBack.judge(1, 0, false, PAST_CAP, STALLED_NOT));
    }

    @Test
    void aWaitingRoomThatLetsNobodyGoForTheStallEndsAsLost() {
        assertEquals(
                Verdict.WAIT, PlayersBack.judge(1, 1, false, PlayersBack.STALL, PlayersBack.STALL.minusSeconds(1)));
        assertEquals(Verdict.LOST, PlayersBack.judge(1, 1, false, PlayersBack.STALL, PlayersBack.STALL));
    }

    @Test
    void maintenanceKeepsTheMissingPlayersWaitingOnPurpose() {
        assertEquals(Verdict.DONE, PlayersBack.judge(2, 2, true, PAST_CAP, STALLED_NOT));
        // More are missing than wait, so somebody left, which maintenance does not explain.
        assertEquals(Verdict.WAIT, PlayersBack.judge(3, 2, true, PAST_CAP, STALLED_NOT));
        assertEquals(Verdict.LOST, PlayersBack.judge(3, 0, true, PAST_CAP, STALLED_NOT));
    }
}
