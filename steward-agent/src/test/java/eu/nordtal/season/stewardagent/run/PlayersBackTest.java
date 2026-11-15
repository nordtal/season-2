package eu.nordtal.season.stewardagent.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.stewardagent.run.PlayersBack.Verdict;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** A late player is waited for, a lost one is warned about, and nothing holds a run forever. */
class PlayersBackTest {

    private static final Duration PAST_CAP = PlayersBack.CAP.plusSeconds(1);

    @Test
    void everyPlayerBackEndsTheCountAtOnce() {
        assertEquals(Verdict.DONE, PlayersBack.judge(0, 0, false, Duration.ZERO));
    }

    @Test
    void beforeTheCapTheCountWaitsWhateverTheWaitingRoomsSay() {
        assertEquals(Verdict.WAIT, PlayersBack.judge(2, 0, false, Duration.ofSeconds(29)));
    }

    @Test
    void pastTheCapAPlayerStillWaitingIsLateAndNotLost() {
        assertEquals(Verdict.WAIT, PlayersBack.judge(3, 1, false, PAST_CAP));
        assertEquals(Verdict.WAIT, PlayersBack.judge(3, 1, false, PlayersBack.LIMIT.minusSeconds(1)));
    }

    @Test
    void pastTheCapNobodyLeftWaitingMeansThePlayersAreLost() {
        assertEquals(Verdict.LOST, PlayersBack.judge(1, 0, false, PAST_CAP));
    }

    @Test
    void aWaitingRoomThatNeverLetsGoEndsAsLostAtTheLimit() {
        assertEquals(Verdict.LOST, PlayersBack.judge(1, 1, false, PlayersBack.LIMIT));
    }

    @Test
    void maintenanceKeepsTheMissingPlayersWaitingOnPurpose() {
        assertEquals(Verdict.DONE, PlayersBack.judge(2, 2, true, PAST_CAP));
        // More are missing than wait, so somebody left, which maintenance does not explain.
        assertEquals(Verdict.WAIT, PlayersBack.judge(3, 2, true, PAST_CAP));
        assertEquals(Verdict.LOST, PlayersBack.judge(3, 0, true, PAST_CAP));
    }
}
