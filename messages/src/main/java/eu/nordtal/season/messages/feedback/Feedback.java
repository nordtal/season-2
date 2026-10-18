package eu.nordtal.season.messages.feedback;

/**
 * The sound categories of the network.
 * The {@code config} group of each module sets how a category sounds.
 */
public enum Feedback {

    /** An objective handed in, a POI created or a little aura earned. */
    SMALL_SUCCESS,

    /** A milestone you finished, a duel won or a wheel prize. */
    BIG_SUCCESS,

    /** Spawn protection, a foreign POI, no spin left or a locked destination. */
    REFUSED,

    /** A lost duel or aura lost to a death. */
    LOSS,

    /** A menu, a grave or another surface opens. */
    SURFACE_OPEN,

    /** A surface closes. */
    SURFACE_CLOSE,

    /** A click in a surface that picks something. */
    SELECT,

    /** The way to the duel arena. */
    TRAVEL,

    /** One tick of a countdown before a duel start or a restart. */
    COUNTDOWN_TICK,

    /** An event everybody hears, such as a milestone finished by others or a phase switch. */
    NETWORK_EVENT,

    /** A staged moment, such as the opening of the season. Its empty sound key is silent. */
    STAGING,

    /** Something given back that everyone nearby hears, such as an emptied grave. */
    RECLAIMED
}
