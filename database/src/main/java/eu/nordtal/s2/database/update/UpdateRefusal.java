package eu.nordtal.s2.database.update;

import eu.nordtal.s2.messages.RefusalReason;

/** Why {@link UpdateDirectory#submit} wrote no run. */
public enum UpdateRefusal implements RefusalReason {

    /** Another run is pending or running. */
    RUN_OPEN,

    /** A take-down named services that are already held. */
    ALREADY_HELD
}
