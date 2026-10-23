package eu.nordtal.season.database.update;

/** Where a run has got to: the {@code stage} column of its row, which steward-agent writes as the run goes. */
public enum RunStage {

    /** Asking every source for the newest version: an update's first stage. */
    RESOLVING,
    /**
     * Every other kind's first stage, and an update's once it is resolved.
     *
     * Nothing has stopped yet. A backup prunes, checks the room and dumps the database in it.
     */
    PLANNED,
    /** The countdown runs and can be cancelled. */
    COUNTDOWN,
    STOPPING,
    /** Saving the volumes, only in a {@code BACKUP} run. */
    BACKING_UP,
    /** Swapping the jars with nothing running on them. */
    INSTALLING,
    STARTING,
    /** Waiting until each service is healthy. */
    VERIFYING,
    DONE,
    /** Nothing needed doing. */
    NOTHING_TO_DO,
    /** Something went wrong. The failed steps say what. */
    FAILED,
    CANCELLED;

    /** Returns whether a run in this stage has stopped. */
    public boolean isFinished() {
        return this == DONE || this == NOTHING_TO_DO || this == FAILED || this == CANCELLED;
    }
}
