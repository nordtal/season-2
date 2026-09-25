package eu.nordtal.s2.common.message.spec;

/** How a text is written, which decides what an editor offers for it. */
public enum TextFormat {
    /** Adventure's MiniMessage: colours, decorations, hover, click, glyphs. */
    MINIMESSAGE,
    /** Discord's markdown: bold, italics, links, mentions. */
    DISCORD_MARKDOWN,
    /** Plain text, drawn as written: a button label, a server list line. */
    PLAIN
}
