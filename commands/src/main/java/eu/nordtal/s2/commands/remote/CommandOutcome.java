package eu.nordtal.s2.commands.remote;

import java.util.Optional;

/**
 * What became of a request, as the asking side sees it; {@code PENDING} and {@code RUNNING} want different sentences.
 *
 * @param status the row's status
 * @param result the answer, already rendered in the asker's language; absent until it is settled
 */
public record CommandOutcome(Status status, Optional<String> result) {

    /** The row's lifecycle. {@code PENDING -> RUNNING -> DONE | FAILED}, or {@code -> EXPIRED}. */
    public enum Status {

        /** Written, and not yet picked up by anything. */
        PENDING,

        /** Claimed by the target, which is running it now. */
        RUNNING,

        /** Ran, and the command answered. */
        DONE,

        /** Claimed and then threw. {@link #result} carries what to tell the asker. */
        FAILED,

        /** The asker stopped waiting before anything claimed it; the target never writes it. */
        EXPIRED;

        /** Returns whether this is the end of the row. */
        public boolean settled() {
            return this == DONE || this == FAILED || this == EXPIRED;
        }
    }

    /** Returns whether the asker should keep waiting. */
    public boolean pending() {
        return !status.settled();
    }
}
