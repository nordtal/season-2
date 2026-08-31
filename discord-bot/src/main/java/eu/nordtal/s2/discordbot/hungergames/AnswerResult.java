package eu.nordtal.s2.discordbot.hungergames;

import java.util.UUID;

/** The outcome of a partner answering an invite, from {@link Teams#accept}/{@link Teams#decline}. */
public record AnswerResult(Status status, UUID teamId, String teamName) {

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
