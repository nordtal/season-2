package eu.nordtal.season.messages.spec;

import java.util.Collection;

/**
 * A place where a text is shown. It decides how an editor previews the text and how long it may be.
 * The limit of a key is the strictest limit of its places.
 */
public enum Display {
    CHAT(Surface.GAME),
    ACTION_BAR(Surface.GAME, 0, true),
    TITLE(Surface.GAME, 0, true),
    SUBTITLE(Surface.GAME, 0, true),
    BOSS_BAR(Surface.GAME, 0, true),
    TAB_LIST(Surface.GAME),
    /** An inventory menu: its title, the name of an item or a line of its tooltip. */
    GUI(Surface.GAME),
    HOLOGRAM(Surface.GAME),
    /** A screen that replaces the game: a refusal, a kick or the resource pack prompt. */
    KICK_SCREEN(Surface.GAME),
    SERVER_LIST(Surface.GAME),
    DISCORD_MESSAGE(Surface.DISCORD, 2000),
    /** The description of an embed or the value of a field. The bot cuts it to the field limit. */
    DISCORD_EMBED(Surface.DISCORD, 4096),
    DISCORD_EMBED_HEADING(Surface.DISCORD, 256),
    /** A Components V2 text block, titles included. A message holds 4000 characters. */
    DISCORD_TEXT(Surface.DISCORD, 4000),
    DISCORD_BUTTON(Surface.DISCORD, 80),
    DISCORD_MODAL(Surface.DISCORD, 45),
    /** A select menu: its placeholder or one of its options. */
    DISCORD_SELECT(Surface.DISCORD, 100),
    /** A channel name. Discord allows 100 characters and no formatting. */
    DISCORD_CHANNEL(Surface.DISCORD, 100),
    /** A slash command's name, description or option. */
    DISCORD_COMMAND(Surface.DISCORD, 100),
    /** The browser renders a page of Steward in its own zone. */
    STEWARD(Surface.STEWARD),
    /** A web push notification, which the server renders as plain text. */
    PUSH(Surface.STEWARD);

    /** Decides how a preview draws a place and whether the preview reaches an admin. */
    public enum Surface {
        GAME,
        DISCORD,
        STEWARD
    }

    private final Surface surface;
    private final int limit;
    private final boolean oneLine;

    Display(final Surface surface) {
        this(surface, 0, false);
    }

    Display(final Surface surface, final int limit) {
        this(surface, limit, false);
    }

    Display(final Surface surface, final int limit, final boolean oneLine) {
        this.surface = surface;
        this.limit = limit;
        this.oneLine = oneLine;
    }

    public Surface surface() {
        return surface;
    }

    /** Returns the character limit of the platform, or {@code 0} if it sets none. */
    public int limit() {
        return limit;
    }

    /** Returns whether every place draws a single line and breaks none. */
    public static boolean oneLine(final Collection<Display> places) {
        return !places.isEmpty() && places.stream().allMatch(place -> place.oneLine);
    }

    /** Returns the strictest limit of the places, or {@code 0} if none sets one. */
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
