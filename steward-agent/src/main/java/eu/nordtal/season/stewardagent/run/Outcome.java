package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.UpdateStatus;

/**
 * The result of one request: the status and the stage it ended in. Its steps say the rest.
 *
 * @param status DONE or FAILED, or RUNNING for a run that a one-shot steward-agent settles
 */
public record Outcome(UpdateStatus status, RunStage stage) {

    /** Ends a run at this stage, which fails it when the stage is {@code FAILED}. */
    static Outcome at(final RunStage stage) {
        return new Outcome(stage == RunStage.FAILED ? UpdateStatus.FAILED : UpdateStatus.DONE, stage);
    }

    static Outcome failed() {
        return at(RunStage.FAILED);
    }

    static Outcome handedOver() {
        return new Outcome(UpdateStatus.RUNNING, RunStage.RESOLVING);
    }

    boolean isHandedOver() {
        return status == UpdateStatus.RUNNING;
    }
}
