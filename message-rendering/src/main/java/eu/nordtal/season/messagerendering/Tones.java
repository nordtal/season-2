package eu.nordtal.season.messagerendering;

import eu.nordtal.season.messages.Tone;
import net.kyori.adventure.text.Component;

public final class Tones {

    private Tones() {}

    /**
     * @param tone {@code null} means {@link Tone#NEUTRAL}
     */
    public static Component paint(final Component message, final Tone tone, final ToneColours colours) {
        return message.colorIfAbsent(colours.of(tone));
    }
}
