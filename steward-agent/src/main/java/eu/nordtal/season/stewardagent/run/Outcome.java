package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.database.update.UpdateStatus;

/**
 * What running one request came to: the status to write back, and the text to write with it.
 *
 * @param status {@link UpdateStatus#DONE} or {@link UpdateStatus#FAILED}, or {@link UpdateStatus#RUNNING} for a run
 *     handed to a one-shot steward-agent, which settles the row itself
 * @param report steward's own rendering, which every surface shows unchanged
 */
public record Outcome(UpdateStatus status, String report) {

    static Outcome done(final String report) {
        return new Outcome(UpdateStatus.DONE, report);
    }

    static Outcome failed(final String report) {
        return new Outcome(UpdateStatus.FAILED, report);
    }

    /** A run another process carries on with: the row stays open, and this report is only its progress. */
    static Outcome handedOver(final String report) {
        return new Outcome(UpdateStatus.RUNNING, report);
    }

    /** Whether the row is left open for the process the run was handed to. */
    boolean isHandedOver() {
        return status == UpdateStatus.RUNNING;
    }
}
