package eu.nordtal.s2.messages;

import java.util.Locale;

/**
 * The one palette's names: how a line or a word should read at a glance.
 * A packaged text names colours only by tone, as {@code <good>}; a command's reply names one as a whole. Each
 * adapter paints it, Discord ignores it, and it names no Adventure type, because the bot loads it.
 */
public enum Tone {

    /** Nothing to flag: an ordinary reply, a chat line's words. */
    NEUTRAL("#d0d0d8"),

    /** It worked, it is current, it came back. */
    GOOD("#8ba888"),

    /** It failed, it is refused. */
    BAD("#a8888b"),

    /** Not a failure, but not what was asked for either: stopped, too late, still waiting. */
    WARN("#b08a4a"),

    /** Supporting detail under a line that carries the news. */
    MUTED("#aaaaaa"),

    /** A heading, a title, an icon that opens a line. */
    ACCENT("#b08a4a"),

    /** The network's own name and links. */
    BRAND("#4a63d8"),

    /** The word in a line that matters most: a name, a number, a place. */
    EMPHASIS("#ffffff"),

    /** Barely there: a hint, a rule, a separator. */
    FAINT("#555555");

    private final String hex;

    Tone(final String hex) {
        this.hex = hex;
    }

    /** Returns the colour the tone has where the {@code colours} settings name none, as {@code #rrggbb}. */
    public String hex() {
        return hex;
    }

    /** Returns the tag a text names the tone by, such as {@code good}. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
