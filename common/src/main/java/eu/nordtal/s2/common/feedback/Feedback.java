package eu.nordtal.s2.common.feedback;

/**
 * The whole sound vocabulary of the network. A call site picks one of these and nothing else.
 *
 * <p>The emptiness is deliberate: no sound name, no pitch, no volume, no method. What a category
 * sounds like is a per-module {@code config.yml} decision parsed into {@link FeedbackSounds}, so a
 * call site can never name a sound of its own. The list is fixed - growing it to fit each new call
 * site is what would stop it being a vocabulary.
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

    /** Going somewhere: the balloon, the farm world reset moving you, the duel arena. */
    TRAVEL,

    /** One tick of a clock running out: a duel start, a farm reset warning, a restart. */
    COUNTDOWN_TICK,

    /** Everybody hears it: a milestone for everyone who did not finish it, a phase switch. */
    NETWORK_EVENT,

    /**
     * A staged moment: the season's opening on a player's first join, and whatever else the staging
     * device is later pointed at. Its {@code sounds.yml} key ships empty - which every module treats
     * as silence - because the sound it wants arrives with the art.
     */
    STAGING
}
