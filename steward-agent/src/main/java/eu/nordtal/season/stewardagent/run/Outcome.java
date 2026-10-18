package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.database.update.UpdateStatus;

/**
 * The result of one request: the status and the report to write back.
 *
 * @param status DONE or FAILED, or RUNNING for a run that a one-shot steward-agent settles
 * @param report the report that every surface shows unchanged
 */
public record Outcome(UpdateStatus status, String report) {

    static Outcome done(final String report) {
        return new Outcome(UpdateStatus.DONE, report);
    }

    static Outcome failed(final String report) {
        return new Outcome(UpdateStatus.FAILED, report);
    }

    static Outcome handedOver(final String report) {
        return new Outcome(UpdateStatus.RUNNING, report);
    }

    boolean isHandedOver() {
        return status == UpdateStatus.RUNNING;
    }
}
