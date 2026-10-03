package eu.nordtal.s2.messages.spec;

/** Where a text is shown, which decides how an editor previews it and how long it may be. */
public enum Display {
    CHAT,
    ACTION_BAR,
    TITLE,
    SUBTITLE,
    BOSS_BAR,
    TAB_LIST,
    SIDEBAR,
    /** An inventory menu: its title, an item's name or a line of its tooltip. */
    GUI,
    /** Floating text in the world. */
    HOLOGRAM,
    /** The screen a refused or kicked player reads. */
    KICK_SCREEN,
    /** The multiplayer server list. */
    SERVER_LIST,
    DISCORD_MESSAGE(2000),
    DISCORD_EMBED(4096),
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
}
