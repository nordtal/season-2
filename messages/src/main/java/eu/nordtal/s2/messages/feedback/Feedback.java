package eu.nordtal.s2.messages.feedback;

/**
 * The whole sound vocabulary of the network; a call site picks one of these and nothing else.
 * What each category sounds like is a per-module {@code config} group decision.
 */
public enum Feedback {

    /** Something small went right: an objective handed in, a POI created, a few aura earned. */
    SMALL_SUCCESS,

    /** Something that took work: a milestone finished by you, a duel won, a wheel prize. */
    BIG_SUCCESS,

    /** The server said no: spawn-protected, not your POI, no spin left, a locked destination. */
    REFUSED,

    /** Something was taken: a duel lost, aura lost to a death. */
    LOSS,

    /** A menu, a grave or any other surface opened. */
    SURFACE_OPEN,

    /** The same surface closed. */
    SURFACE_CLOSE,

    /** A click inside a surface that picked something. */
    SELECT,

    /** Going somewhere: the balloon, or the duel arena. */
    TRAVEL,

    /** One tick of a clock running out: a duel start, or a restart. */
    COUNTDOWN_TICK,

    /** Everybody hears it: a milestone for everyone who did not finish it, a phase switch. */
    NETWORK_EVENT,

    /** A staged moment, such as the season's opening; its sound key ships empty, which means silence. */
    STAGING,

    /** Something is given back, heard by everyone nearby, such as an emptied grave settling. */
    RECLAIMED
}
