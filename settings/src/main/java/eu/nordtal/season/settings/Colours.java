package eu.nordtal.season.settings;

import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.messages.Tone;
import java.util.EnumMap;
import java.util.Map;

public final class Colours {

    /** The tone colours of every Paper and proxy service. */
    public static final Group<ColoursSpec> GROUP = Group.of("colours", ColoursSpec.class);

    private Colours() {}

    /** Returns the colour of each tone that the settings of {@code service} name, the defaults where unreadable. */
    public static Map<Tone, String> current(final SettingStore store, final String service) {
        try {
            return declared(DatabaseSettings.current(store, service, GROUP));
        } catch (final SettingsException unreadable) {
            final Map<Tone, String> defaults = new EnumMap<>(Tone.class);
            for (final Tone tone : Tone.values()) {
                defaults.put(tone, tone.hex());
            }
            return defaults;
        }
    }

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
