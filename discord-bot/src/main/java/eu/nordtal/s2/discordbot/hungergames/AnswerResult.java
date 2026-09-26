package eu.nordtal.s2.discordbot.hungergames;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of a partner answering an invite, from {@link Teams#accept}/{@link Teams#decline}.
 *
 * @param teamId null unless {@link #status()} is {@link Status#ANSWERED}
 * @param teamName null unless {@link #status()} is {@link Status#ANSWERED}
 */
public record AnswerResult(
        Status status, @Nullable UUID teamId, @Nullable String teamName) {

    public static AnswerResult answered(final UUID teamId, final String teamName) {
        return new AnswerResult(Status.ANSWERED, teamId, teamName);
    }

    /** The invite no longer exists, already got an answer, or was for somebody else's account. */
    public static AnswerResult notPending() {
        return new AnswerResult(Status.NOT_PENDING, null, null);
    }

    public enum Status {
        ANSWERED,
        NOT_PENDING
    }
}
