package eu.nordtal.s2.common.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * A {@link Tone}, as a colour on a Minecraft component. It paints with {@code colorIfAbsent}, so a
 * tone is a default that a line's own MiniMessage can override.
 *
 * <p>Separate from the enum because it names Adventure, which {@code discord-bot} and
 * {@code updater} do not have on their classpath even though they load {@link Tone}.
 */
public final class Tones {

    /** Arriving. */
    private static final TextColor GOOD_GREEN = TextColor.fromHexString("#8ba888");

    /** Leaving. The only warm red the network draws, so a failed line is findable in a long list. */
    private static final TextColor BAD_RED = TextColor.fromHexString("#a8888b");

    /** The accent, what an announcement already wears. */
    private static final TextColor ACCENT = TextColor.fromHexString("#b08a4a");

    /** The sentence colour. Both the supporting detail and an ordinary reply. */
    private static final TextColor GREY = NamedTextColor.GRAY;

    private Tones() {
    }

    /**
     * @param message the rendered line
     * @param tone    how it should read; {@code null} is treated as {@link Tone#NEUTRAL}
     * @return the same line, with a colour filled in where the message did not set one
     */
    public static Component paint(final Component message, final Tone tone) {
        return message.colorIfAbsent(colourOf(tone));
    }

    /**
     * The palette: the network's own colours rather than the vanilla chat constants, which would
     * read as a terminal beside every other surface.
     */
    private static TextColor colourOf(final Tone tone) {
        if (tone == null) {
            return GREY;
        }
        return switch (tone) {
            case GOOD -> GOOD_GREEN;
            case BAD -> BAD_RED;
            case WARN -> ACCENT;
            case MUTED -> GREY;
            // Grey, not "no colour" and not white: unpainted hands the line to the client's
            // default, which differs by surface, and white would make an ordinary reply the
            // brightest thing on screen.
            case NEUTRAL -> GREY;
        };
    }
}
