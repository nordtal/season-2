package eu.nordtal.season.messages.value;

/** The name of an update run for the players. */
public enum Occasion {

    /** An update request of the kind {@code DOWN}. */
    DOWN,

    /** An update request of the kind {@code RESTART}. */
    RECREATE,

    /** An update request of the kind {@code BACKUP}. */
    BACKUP,

    /** An update request of the kind {@code UPDATE}. */
    UPDATE,

    /** No waiting room runs during the run, so the players are disconnected. It replaces every other kind. */
    MAINTENANCE
}
