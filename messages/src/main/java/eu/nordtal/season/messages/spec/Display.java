package eu.nordtal.season.messages.spec;

import java.util.Collection;

/**
 * A place a text is shown, which decides how an editor previews it there and how long it may be.
 * A key names every place it really appears, and the strictest of their limits is its own.
 */
public enum Display {
    CHAT,
    ACTION_BAR,
    TITLE,
    SUBTITLE,
    BOSS_BAR,
    TAB_LIST,
    /** An inventory menu: its title, an item's name or a line of its tooltip. */
    GUI,
    /** Floating text in the world. */
    HOLOGRAM,
    /** A screen the client shows in place of the game: a refusal, a kick, or the resource pack prompt. */
    KICK_SCREEN,
    /** The multiplayer server list. */
    SERVER_LIST,
    DISCORD_MESSAGE(2000),
    /** An embed's description or a field's value, which the bot cuts to a field's shorter limit. */
    DISCORD_EMBED(4096),
    /** An embed's title or a field's name. */
    DISCORD_EMBED_HEADING(256),
    DISCORD_BUTTON(80),
    DISCORD_MODAL(45),
    /** A select menu: its placeholder or one of its options. */
    DISCORD_SELECT(100),
    /** A channel's name, which Discord keeps to 100 characters and no formatting. */
    DISCORD_CHANNEL(100),
    /** A slash command's name, description or option. */
    DISCORD_COMMAND(100),
    /** Steward's own page, which the browser renders in its own zone. */
    STEWARD,
    /** A web push notification, rendered on the server as plain text. */
    PUSH;

    private final int limit;

    Display() {
        this(0);
    }

    Display(final int limit) {
        this.limit = limit;
    }

    /** Returns how many characters the platform shows here, {@code 0} where it sets no limit. */
    public int limit() {
        return limit;
    }

    /** Returns the strictest limit of the places, {@code 0} when none of them sets one. */
    public static int strictest(final Collection<Display> places) {
        int strictest = 0;
        for (final Display place : places) {
            if (place.limit > 0 && (strictest == 0 || place.limit < strictest)) {
                strictest = place.limit;
            }
        }
        return strictest;
    }
}
