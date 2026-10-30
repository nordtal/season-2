package eu.nordtal.season.database.inbox;

import eu.nordtal.season.database.DatabaseMessages;
import eu.nordtal.season.messages.Refusal;
import eu.nordtal.season.messages.RefusalReason;
import java.util.List;
import java.util.Map;

/** Why a server refused a request in its inbox or the same action typed on its console. */
public enum ServerRefusal implements RefusalReason {

    /** No milestone is active. */
    NO_ACTIVE_MILESTONE,

    /** The key names no open objective of the active milestone. */
    NO_SUCH_OBJECTIVE,

    /** The key names a milestone that is not active. */
    MILESTONE_NOT_ACTIVE,

    /** A game starts only during the start event. */
    WRONG_PHASE,

    /** No game is open for registration. */
    NO_GAME,

    /** The game is past registration. */
    WRONG_STATE,

    /** Too few participants for the border calculation. */
    BELOW_HARD_MINIMUM,

    /** Fewer participants than recommended, and the asker did not confirm. */
    BELOW_SOFT_MINIMUM,

    /** The player of a preview, or the admin of an editor, is not on this server. */
    NOT_HERE,

    /** Discord did not deliver a preview as a direct message. */
    NOT_DELIVERED,

    /** The server would not start with a change to its settings. */
    SETTINGS_REFUSED,

    /** The player holds the other mode, the slot holds another stack than the one shown, or the state is not here. */
    STACK_CHANGED;

    /** Returns the names of the values that {@link #with} takes, in order: the message's arguments and columns. */
    public List<String> arguments() {
        return switch (this) {
            case NO_ACTIVE_MILESTONE, NO_GAME, NOT_HERE, NOT_DELIVERED, STACK_CHANGED -> List.of();
            case NO_SUCH_OBJECTIVE -> List.of("key");
            case MILESTONE_NOT_ACTIVE -> List.of("key", "active");
            case WRONG_PHASE -> List.of("phase");
            case WRONG_STATE -> List.of("state");
            case BELOW_HARD_MINIMUM -> List.of("minimum", "count");
            case BELOW_SOFT_MINIMUM -> List.of("count", "minimum");
            case SETTINGS_REFUSED -> List.of("service", "detail");
        };
    }

    /** Returns this refusal from its values by name, as a stored refusal holds them. */
    public Refusal with(final Map<String, ?> named) {
        return with(arguments().stream().map(named::get).toArray());
    }

    /** Returns this refusal with its message from the database bundle. */
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
                    case NOT_HERE -> words.notHere();
                    case NOT_DELIVERED -> words.notDelivered();
                    case SETTINGS_REFUSED -> words.settingsRefused(String.valueOf(args[0]), String.valueOf(args[1]));
                    case STACK_CHANGED -> words.stackChanged();
                });
    }

    private static long count(final Object value) {
        return ((Number) value).longValue();
    }
}
