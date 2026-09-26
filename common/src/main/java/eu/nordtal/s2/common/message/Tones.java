package eu.nordtal.s2.common.message;

import net.kyori.adventure.text.Component;

/**
 * Paints a {@link Tone} on a component with {@code colorIfAbsent}, so a line's own markup wins.
 *
 * Separate from the enum, which modules without Adventure load. The colours come per plugin as
 * {@link ToneColours}.
 */
public final class Tones {

    private Tones() {}

    /**
     * @param message the rendered line
     * @param tone    how it should read; {@code null} is treated as {@link Tone#NEUTRAL}
     * @param colours the palette to paint with - the network's own colours rather than the vanilla
     *                chat constants, which would read as a terminal beside every other surface
     * @return the same line, with a colour filled in where the message did not set one
     */
    public static Component paint(final Component message, final Tone tone, final ToneColours colours) {
        return message.colorIfAbsent(colours.of(tone));
    }
}
