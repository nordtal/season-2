package eu.nordtal.season.settings;

import eu.nordtal.season.messages.Tone;
import java.util.EnumMap;
import java.util.Map;

public final class Colours {

    /** The tone colours of every Paper and proxy service. */
    public static final Group<ColoursSpec> GROUP = Group.of("colours", ColoursSpec.class);

    private Colours() {}

    /** Returns the hex value of each tone. A {@link Tone} without a key here does not compile. */
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
                        case ACCENT -> spec.accent();
                        case BRAND -> spec.brand();
                        case EMPHASIS -> spec.emphasis();
                        case FAINT -> spec.faint();
                    });
        }
        return declared;
    }
}
