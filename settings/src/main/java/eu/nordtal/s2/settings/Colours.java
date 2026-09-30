package eu.nordtal.s2.settings;

import eu.nordtal.s2.messages.Tone;
import java.util.EnumMap;
import java.util.Map;

/** The tone colours a group of {@link ColoursSpec} declares. */
public final class Colours {

    private Colours() {}

    /** Returns each tone's declared hex value; a new {@link Tone} stops this compiling until it has a key. */
    public static Map<Tone, String> declared(final ColoursSpec spec) {
        final Map<Tone, String> declared = new EnumMap<>(Tone.class);
        for (final Tone tone : Tone.values()) {
            declared.put(
                    tone,
                    switch (tone) {
                        case GOOD -> spec.good();
                        case BAD -> spec.bad();
                        case WARN -> spec.warn();
                        case NEUTRAL -> spec.neutral();
                        case MUTED -> spec.muted();
                    });
        }
        return declared;
    }
}
