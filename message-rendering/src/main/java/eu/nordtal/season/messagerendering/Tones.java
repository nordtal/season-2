package eu.nordtal.season.messagerendering;

import eu.nordtal.season.messages.Tone;
import net.kyori.adventure.text.Component;

/**
 * Paints a {@link Tone} on a component with {@code colorIfAbsent}, so a line's own markup wins.
 * Separate from the enum, which modules without Adventure load.
 */
public final class Tones {

    private Tones() {}

    /**
     * Returns {@code message} with the tone's colour filled in where the message set none.
     *
     * @param tone {@code null} is treated as {@link Tone#NEUTRAL}
     */
    public static Component paint(final Component message, final Tone tone, final ToneColours colours) {
        return message.colorIfAbsent(colours.of(tone));
    }
}
