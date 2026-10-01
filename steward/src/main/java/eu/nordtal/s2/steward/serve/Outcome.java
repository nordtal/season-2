package eu.nordtal.s2.steward.serve;

import eu.nordtal.s2.database.update.UpdateStatus;

/**
 * What running one request came to: the status to write back, and the text to write with it.
 *
 * @param status {@link UpdateStatus#DONE} or {@link UpdateStatus#FAILED}, or {@link UpdateStatus#PENDING} for a
 *               run handed to the steward it installed
 * @param report steward's own rendering, which every surface shows unchanged
 */
public record Outcome(UpdateStatus status, String report) {

    static Outcome done(final String report) {
        return new Outcome(UpdateStatus.DONE, report);
    }

    static Outcome failed(final String report) {
        return new Outcome(UpdateStatus.FAILED, report);
    }

    static Outcome handedOver(final String report) {
        return new Outcome(UpdateStatus.PENDING, report);
    }

    /** Whether the request goes back into the inbox for the next steward rather than being finished. */
    boolean isHandedOver() {
        return status == UpdateStatus.PENDING;
    }
}
