package eu.nordtal.s2.steward.worker.serve;

import eu.nordtal.s2.common.update.UpdateStatus;

/**
 * What running one request came to: the status to write back, and the text to write with it.
 *
 * @param status {@link UpdateStatus#DONE} or {@link UpdateStatus#FAILED}, or {@link UpdateStatus#PENDING} for a run
 *               handed to the steward-worker it installed - see {@link Handover}
 * @param report what a person reads afterwards - in a Discord embed, in a chat line, or in the
 *               table. Always steward-worker's own rendering, never a second one, so every surface
 *               shows the same words steward-worker prints on the host
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

    /** Whether the request goes back into the inbox for the next worker rather than being finished. */
    boolean isHandedOver() {
        return status == UpdateStatus.PENDING;
    }
}
