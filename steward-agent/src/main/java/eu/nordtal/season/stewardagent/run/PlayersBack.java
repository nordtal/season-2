package eu.nordtal.season.stewardagent.run;

import java.time.Duration;

/**
 * Decides how the count of the players a run moved out goes on, from plain values.
 *
 * A player still in a waiting room is late, not lost: the proxy lets them on once their server answers.
 */
final class PlayersBack {

    /** How long the players moved out may take to come back once the standbys are closed. */
    static final Duration CAP = Duration.ofSeconds(30);

    /**
     * How long the count waits at most while players who left still wait.
     *
     * It covers the standby proxy's minute and a full SMP queue, so only a stuck waiting room reaches it.
     */
    static final Duration LIMIT = Duration.ofMinutes(5);

    private PlayersBack() {}

    /** What the count does next. */
    enum Verdict {
        /** Every player came back, or the rest wait on purpose. */
        DONE,
        /** Players are still on their way. */
        WAIT,
        /** Fewer players came back than were moved out, and nobody is left waiting for them. */
        LOST
    }

    /**
     * Returns what the count does next.
     *
     * @param missing the players moved out who have not come back
     * @param waiting the players who left and still wait in a waiting room, zero when no fresh roster says
     * @param maintenance whether maintenance keeps members in the waiting room
     * @param elapsed the time since the count began
     */
    static Verdict judge(final int missing, final int waiting, final boolean maintenance, final Duration elapsed) {
        if (missing <= 0) {
            return Verdict.DONE;
        }
        if (elapsed.compareTo(CAP) < 0) {
            return Verdict.WAIT;
        }
        if (maintenance && waiting >= missing) {
            return Verdict.DONE;
        }
        if (waiting > 0 && elapsed.compareTo(LIMIT) < 0) {
            return Verdict.WAIT;
        }
        return Verdict.LOST;
    }
}
