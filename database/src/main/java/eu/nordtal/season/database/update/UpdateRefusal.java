package eu.nordtal.season.database.update;

import eu.nordtal.season.messages.RefusalReason;

/** Why {@link UpdateDirectory#submit} wrote no run. */
public enum UpdateRefusal implements RefusalReason {

    /** Another run is pending or running. */
    RUN_OPEN,

    /** A take-down named services that are already held. */
    ALREADY_HELD
}
