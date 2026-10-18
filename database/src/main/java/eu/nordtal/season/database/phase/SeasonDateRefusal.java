package eu.nordtal.season.database.phase;

import eu.nordtal.season.messages.RefusalReason;

/** Why a season date was not written. */
public enum SeasonDateRefusal implements RefusalReason {

    /** The date is in the past. */
    IN_THE_PAST,

    /** The network would open after paid access starts, or paid access would start before the network opens. */
    OUT_OF_ORDER,

    /** Paid access is running, so its start does not move. */
    SMP_RUNNING
}
