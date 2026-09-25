package eu.nordtal.s2.common.message.spec;

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
    DISCORD_MESSAGE,
    DISCORD_EMBED,
    DISCORD_BUTTON,
    DISCORD_MODAL,
    /** A select menu: its placeholder or one of its options. */
    DISCORD_SELECT,
    /** A channel's name, which Discord keeps to 100 characters and no formatting. */
    DISCORD_CHANNEL,
    /** A slash command's name, description or option. */
    DISCORD_COMMAND
}
