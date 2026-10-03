package eu.nordtal.s2.database.inbox;

import eu.nordtal.s2.database.DatabaseMessages;
import eu.nordtal.s2.messages.Refusal;
import eu.nordtal.s2.messages.RefusalReason;

/** Why a server refused a request in its inbox or the same action typed on its console. */
public enum ServerRefusal implements RefusalReason {

    /** No milestone is active, so nothing of the track can be closed. */
    NO_ACTIVE_MILESTONE,

    /** The key names no open objective of the active milestone. */
    NO_SUCH_OBJECTIVE,

    /** The key names a milestone that is not the active one. */
    MILESTONE_NOT_ACTIVE,

    /** A game only starts during the start event. */
    WRONG_PHASE,

    /** No game is open for registration. */
    NO_GAME,

    /** The game is past its registration. */
    WRONG_STATE,

    /** Too few participants for the border arithmetic. */
    BELOW_HARD_MINIMUM,

    /** Fewer participants than recommended, and the asker has not confirmed. */
    BELOW_SOFT_MINIMUM;

    /** Returns this refusal with the message the database bundle words it in. */
    public Refusal with(final Object... args) {
        final DatabaseMessages.ServerRefusals words = DatabaseMessages.MESSAGES.server();
        return new Refusal(
                this,
                switch (this) {
                    case NO_ACTIVE_MILESTONE -> words.noActiveMilestone();
                    case NO_SUCH_OBJECTIVE -> words.noSuchObjective(String.valueOf(args[0]));
                    case MILESTONE_NOT_ACTIVE ->
                        words.milestoneNotActive(String.valueOf(args[0]), String.valueOf(args[1]));
                    case WRONG_PHASE -> words.wrongPhase(String.valueOf(args[0]));
                    case NO_GAME -> words.noGame();
                    case WRONG_STATE -> words.wrongState(String.valueOf(args[0]));
                    case BELOW_HARD_MINIMUM -> words.belowHardMinimum(count(args[0]), count(args[1]));
                    case BELOW_SOFT_MINIMUM -> words.belowSoftMinimum(count(args[0]), count(args[1]));
                });
    }

    private static long count(final Object value) {
        return ((Number) value).longValue();
    }
}
