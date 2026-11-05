package eu.nordtal.season.proxy.pack;

/** What the season holds in the waiting room besides the pack and the backend. */
public enum SeasonHold {

    /** Nothing: the gate admitted the player to the active mode. */
    NONE,

    /** Maintenance holds every player but the admins. */
    MAINTENANCE,

    /** A mode switch holds every player, admins included. */
    SWITCH
}
