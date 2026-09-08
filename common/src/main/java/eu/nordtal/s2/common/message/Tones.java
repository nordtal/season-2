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

    private Tones() {
    }

    /**
     * @param message the rendered line
     * @param tone    how it should read; {@code null} is treated as {@link Tone#NEUTRAL}
     * @return the same line, with a colour filled in where the message did not set one
     */
    public static Component paint(final Component message, final Tone tone) {
        final TextColor colour = colourOf(tone);
        return colour == null ? message : message.colorIfAbsent(colour);
    }

    /**
     * The palette. Four colours and "leave it alone", chosen so a report can be read without being
     * read: the failed line is the only red one, and the versions under a service recede.
     */
    private static TextColor colourOf(final Tone tone) {
        if (tone == null) {
            return null;
        }
        return switch (tone) {
            case GOOD -> NamedTextColor.GREEN;
            case BAD -> NamedTextColor.RED;
            case WARN -> NamedTextColor.GOLD;
            case MUTED -> NamedTextColor.GRAY;
            // Not white: the client's own default for chat is what an unstyled line already gets,
            // and forcing white here would make an ordinary line disagree with every other one the
            // network sends.
            case NEUTRAL -> null;
        };
    }
}
