package eu.nordtal.s2.common.command;

import java.util.Optional;

/**
 * What became of a request, as the asking side sees it.
 *
 * <h2>Four states and not two</h2>
 * "Did it work" is not enough to say anything useful to somebody watching a spinner. {@code PENDING}
 * and {@code RUNNING} both mean keep waiting and mean different things when the wait runs out - a
 * request nobody ever claimed is a target that is down, and one claimed and never settled is a
 * target that is up and stuck. Those want different sentences, so they stay distinguishable.
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

        /**
         * The asker stopped waiting before anything claimed it.
         *
         * <p>Never written by the target - it refuses to claim an expired row instead, so this
         * status means precisely "nothing ever picked this up", which is the one diagnosis worth
         * having: the process that owns the command is not listening.</p>
         */
        EXPIRED;

        /** Whether this is the end of the row. */
        public boolean settled() {
            return this == DONE || this == FAILED || this == EXPIRED;
        }
    }

    /** Whether the asker should keep waiting. */
    public boolean pending() {
        return !status.settled();
    }
}
