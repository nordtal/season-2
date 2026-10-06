package eu.nordtal.season.discordbot;

/**
 * The one set of emojis a Discord message uses to show a status or an action, in front of the text it belongs to.
 *
 * Every embed and the admin log take theirs from here; no other class types an emoji.
 */
public enum Mark {

    /** Finished well: a run that is done, a service that is healthy, an alert that cleared. */
    DONE("✅"),

    /** Finished badly, or down: a failed run or service, an alert that needs an admin now. */
    FAILED("🛑"),

    /** Needs a look but has not failed: a warning alert, a missing season start. */
    WARNING("⚠️"),

    /** A step that is under way. */
    WORKING("🔄"),

    /** A step that has not begun, or a countdown. */
    WAITING("⏳"),

    /** Nothing to do for this one. */
    UNCHANGED("➖"),

    /** A run that has not been read yet. */
    LOOKING("🔍"),

    /** A run that is planned. */
    PLANNED("📋"),

    /** A run that was cancelled. */
    STOPPED("⏹️"),

    /** A payment booked. */
    PAID("💶"),

    /** A refusal that keeps something shut, such as too many link codes. */
    LOCKED("🔒"),

    /** Access granted. */
    GRANTED("🎟️"),

    /** Access revoked. */
    REVOKED("🚫"),

    /** An account linked. */
    LINKED("🔗"),

    /** An account unlinked. */
    UNLINKED("✂️"),

    /** A play time set. */
    TIMED("⏱️"),

    /** An action with no mark of its own. */
    NOTED("📝");

    private final String symbol;

    Mark(final String symbol) {
        this.symbol = symbol;
    }

    /** Returns the emoji itself. */
    public String symbol() {
        return symbol;
    }

    /** Returns {@code text} with this mark in front, the way every card title and line starts. */
    public String before(final String text) {
        return symbol + " " + text;
    }
}
