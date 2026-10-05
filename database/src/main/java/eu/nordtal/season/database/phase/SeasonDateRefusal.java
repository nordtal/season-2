package eu.nordtal.season.database.phase;

import eu.nordtal.season.messages.RefusalReason;

/** Why a season date was not written. */
public enum SeasonDateRefusal implements RefusalReason {

    /** The date has already happened. */
    IN_THE_PAST,

    /** The network would open after paid time starts, or paid time would start before it opens. */
    OUT_OF_ORDER,

    /** Paid time is already being used up, so its start no longer moves. */
    SMP_RUNNING
}
