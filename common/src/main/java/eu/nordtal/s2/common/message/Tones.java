package eu.nordtal.s2.common.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * A {@link Tone}, as a colour on a Minecraft component.
 *
 * <h2>Why {@code colorIfAbsent} and not {@code color}</h2>
 * A key in a <em>process's own</em> bundle may carry MiniMessage, and the tone must not repaint what
 * the wording deliberately styled - the tone is a default the author of a line can always override.
 * The shared bundle carries no markup at all, so for {@code /update} today this always applies; the
 * moment a surface rewords one of those keys in its own bundle, with colour, that colour wins.
 *
 * <p>Separate from the enum because it names Adventure, which two of the five processes do not have
 * on their classpath: {@code discord-bot} and {@code updater} shade {@code :common} and load
 * {@link Tone} through {@code NordtalUser}'s signature. A method here would drag Adventure into that
 * load.</p>
 */
public final class Tones {

    /** docs/presentation.md#the-palette: arriving. */
    private static final TextColor GOOD_GREEN = TextColor.fromHexString("#8ba888");

    /** docs/presentation.md#the-palette: leaving. The only warm red the network draws. */
    private static final TextColor BAD_RED = TextColor.fromHexString("#a8888b");

    /** docs/presentation.md#the-palette: the accent, what an announcement already wears. */
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
     * The palette. Four colours and a quiet one, and every value is the pack's own rather than a
     * named constant: {@code NamedTextColor.GREEN} and {@code RED} are the vanilla chat colours,
     * and a reply in them reads as a terminal standing next to a network whose every other surface
     * is drawn from {@code docs/presentation.md}. The two arrival colours already exist there for
     * exactly this pair of meanings, and the accent is what an announcement already uses.
     *
     * <p>The one thing this palette must keep doing is what it was built for: the failed line has
     * to be findable in a list of forty. {@code #a8888b} is quieter than vanilla red and still the
     * only warm-red line on the surface.</p>
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
            // Grey, not "no colour" and not white (owner, 2026-09-09). Leaving it unpainted hands
            // the line to the client's default, which differs by surface and by what came before
            // it in the same message; white would make an ordinary reply the brightest thing on
            // screen. Grey is what docs/presentation.md already gives a sentence the network says
            // about itself, which is what a NEUTRAL reply is.
            case NEUTRAL -> GREY;
        };
    }
}
