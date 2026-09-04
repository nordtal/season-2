package eu.nordtal.s2.common.feedback;

/**
 * The whole sound vocabulary of the network. A call site picks one of these and nothing else.
 *
 * <p><b>This enum is the point of the design, and its emptiness is deliberate.</b> It carries no
 * sound name, no pitch, no volume and no method: a call site that could name a sound would
 * eventually name a different one for the same kind of event, and no amount of care prevents that -
 * it is what happened to season 1's chimes. What a category sounds like is a per-module
 * {@code config.yml} decision, parsed into {@link FeedbackSounds}, and changing it is an edit rather
 * than a release.
 *
 * <p>Nine categories, one of which has an open and a close half - so ten constants. If a call site
 * cannot be expressed with one of them, that is a question for the owner and not a licence to add an
 * eleventh: the moment the list grows to fit each new call site it stops being a vocabulary.
 *
 * <p>docs/presentation.md section 4 is where the categories are described in prose; that document is
 * the authority and this one is the code.
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
    NETWORK_EVENT
}
