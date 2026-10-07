package eu.nordtal.season.messages.spec;

import java.util.Collection;

/**
 * A place a text is shown, which decides how an editor previews it there and how long it may be.
 * A key names every place it really appears, and the strictest of their limits is its own.
 */
public enum Display {
    CHAT(Surface.GAME),
    ACTION_BAR(Surface.GAME),
    TITLE(Surface.GAME),
    SUBTITLE(Surface.GAME),
    BOSS_BAR(Surface.GAME),
    TAB_LIST(Surface.GAME),
    /** An inventory menu: its title, an item's name or a line of its tooltip. */
    GUI(Surface.GAME),
    /** Floating text in the world. */
    HOLOGRAM(Surface.GAME),
    /** A screen the client shows in place of the game: a refusal, a kick, or the resource pack prompt. */
    KICK_SCREEN(Surface.GAME),
    /** The multiplayer server list. */
    SERVER_LIST(Surface.GAME),
    DISCORD_MESSAGE(Surface.DISCORD, 2000),
    /** An embed's description or a field's value, which the bot cuts to a field's shorter limit. */
    DISCORD_EMBED(Surface.DISCORD, 4096),
    /** An embed's title or a field's name. */
    DISCORD_EMBED_HEADING(Surface.DISCORD, 256),
    DISCORD_BUTTON(Surface.DISCORD, 80),
    DISCORD_MODAL(Surface.DISCORD, 45),
    /** A select menu: its placeholder or one of its options. */
    DISCORD_SELECT(Surface.DISCORD, 100),
    /** A channel's name, which Discord keeps to 100 characters and no formatting. */
    DISCORD_CHANNEL(Surface.DISCORD, 100),
    /** A slash command's name, description or option. */
    DISCORD_COMMAND(Surface.DISCORD, 100),
    /** Steward's own page, which the browser renders in its own zone. */
    STEWARD(Surface.STEWARD),
    /** A web push notification, rendered on the server as plain text. */
    PUSH(Surface.STEWARD);

    /** Where a place is, which decides how a preview draws it and whether one reaches an admin there. */
    public enum Surface {
        GAME,
        DISCORD,
        /** Steward's page and its notifications. */
        STEWARD
    }

    private final Surface surface;
    private final int limit;

    Display(final Surface surface) {
        this(surface, 0);
    }

    Display(final Surface surface, final int limit) {
        this.surface = surface;
        this.limit = limit;
    }

    /** Returns where this place is. */
    public Surface surface() {
        return surface;
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
