package eu.nordtal.season.stewardagent.run;

import eu.nordtal.season.database.update.RunStage;
import eu.nordtal.season.database.update.UpdateStatus;

/**
 * The result of one request: the status and the stage it ended in. Its steps say the rest.
 *
 * @param status DONE or FAILED, or RUNNING for a run that a one-shot steward-agent settles
 * @param swapsPack whether a live swap follows once the row is settled, because the run changed only the pack
 */
public record Outcome(UpdateStatus status, RunStage stage, boolean swapsPack) {

    Outcome(final UpdateStatus status, final RunStage stage) {
        this(status, stage, false);
    }

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

    /** Returns this outcome followed by a live swap, unless the run was cancelled or handed over. */
    Outcome swappingPack() {
        return isHandedOver() || stage == RunStage.CANCELLED ? this : new Outcome(status, stage, true);
    }

    boolean isHandedOver() {
        return status == UpdateStatus.RUNNING;
    }
}
