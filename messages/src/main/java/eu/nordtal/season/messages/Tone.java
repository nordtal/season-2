package eu.nordtal.season.messages;

import java.util.Locale;

/**
 * The names of the palette. A packaged text names a colour only by its tone, as {@code <good>}.
 * A command's reply names one tone for the whole reply. Each adapter paints the tones and Discord
 * ignores them. The enum names no Adventure type, because the bot loads it.
 */
public enum Tone {

    /** An ordinary reply or chat line. */
    NEUTRAL("#d0d0d8"),

    /** A success. */
    GOOD("#8ba888"),

    /** A failure or a refusal. */
    BAD("#a8888b"),

    /** Neither success nor failure, such as stopped or waiting. */
    WARN("#b08a4a"),

    /** Detail under a main line. */
    MUTED("#aaaaaa"),

    /** A heading, title or opening icon. */
    ACCENT("#b08a4a"),

    /** The name and links of the network. */
    BRAND("#4a63d8"),

    /** The key word of a line, such as a name, number or place. */
    EMPHASIS("#ffffff"),

    /** A hint, rule or separator. */
    FAINT("#555555");

    private final String hex;

    Tone(final String hex) {
        this.hex = hex;
    }

    /** Returns the default colour as {@code #rrggbb}. */
    public String hex() {
        return hex;
    }

    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
