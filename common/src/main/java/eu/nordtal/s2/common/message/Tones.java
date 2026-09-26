package eu.nordtal.s2.common.message;

import net.kyori.adventure.text.Component;

/**
 * A {@link Tone}, as a colour on a Minecraft component. It paints with {@code colorIfAbsent}, so a
 * tone is a default that a line's own MiniMessage can override.
 *
 * <p>Separate from the enum because it names Adventure, which {@code discord-bot} and
 * {@code steward-worker} do not have on their classpath even though they load {@link Tone}.
 *
 * <p>The five colours themselves are not here (season-2-ingame/22): {@code :common} does not depend
 * on jcore, so the {@code @ConfigSpec} that reads a {@code colours.yml} lives per plugin, one per
 * module that paints a reply - the same split {@code FeedbackSounds} and a platform's own sound
 * adapter draw for a sound. {@link ToneColours} is the parsed result; a caller hands one in.
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
