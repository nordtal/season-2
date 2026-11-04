package eu.nordtal.season.database.season;

import eu.nordtal.season.messages.RefusalReason;

/** Why a change of the launch date was not written. */
public enum SeasonRefusal implements RefusalReason {

    /** The date has passed. */
    IN_THE_PAST,

    /** The season is already launched, so it cannot launch now. */
    ALREADY_LAUNCHED,

    /** The season is closed or counting down, so there is nothing to close. */
    NOT_LAUNCHED
}
