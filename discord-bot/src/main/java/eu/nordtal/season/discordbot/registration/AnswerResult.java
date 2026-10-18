package eu.nordtal.season.discordbot.registration;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of {@link Teams#accept} and {@link Teams#decline}.
 *
 * @param teamId null unless {@link #status()} is {@link Status#ANSWERED}
 * @param teamName null unless {@link #status()} is {@link Status#ANSWERED}
 */
public record AnswerResult(
        Status status, @Nullable UUID teamId, @Nullable String teamName) {

    public static AnswerResult answered(final UUID teamId, final String teamName) {
        return new AnswerResult(Status.ANSWERED, teamId, teamName);
    }

    /** The invite does not exist, has an answer, or belongs to another account. */
    public static AnswerResult notPending() {
        return new AnswerResult(Status.NOT_PENDING, null, null);
    }

    /** The round is closed while a game of it is under way. The invite stays open. */
    public static AnswerResult closed() {
        return new AnswerResult(Status.CLOSED, null, null);
    }

    public enum Status {
        ANSWERED,
        CLOSED,
        NOT_PENDING
    }
}
