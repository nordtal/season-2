package eu.nordtal.s2.messagerendering;

import eu.nordtal.s2.messages.Tone;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.kyori.adventure.text.format.TextColor;
import org.jspecify.annotations.Nullable;

/**
 * The five {@link Tone} colours, parsed once and then answered from memory.
 *
 * A value that is not a hex colour is reported once through {@code problems} and replaced by the tone's default.
 */
public final class ToneColours {

    private static final Map<Tone, TextColor> DEFAULT_MAP = defaults();

    public static final ToneColours DEFAULTS = new ToneColours(DEFAULT_MAP);

    private final Map<Tone, TextColor> byTone;

    private ToneColours(final Map<Tone, TextColor> byTone) {
        this.byTone = byTone;
    }

    /**
     * Parses a module's {@code colours.yml}.
     *
     * @param declared a hex string per tone; a tone it does not carry takes {@link #DEFAULTS} silently
     * @param problems where a value replaced by its default is reported, once each
     */
    public static ToneColours parse(final Map<Tone, String> declared, final Consumer<String> problems) {
        Objects.requireNonNull(declared, "declared");
        Objects.requireNonNull(problems, "problems");
        final Map<Tone, TextColor> parsed = new EnumMap<>(Tone.class);
        for (final Tone tone : Tone.values()) {
            final TextColor fallback = Objects.requireNonNull(DEFAULT_MAP.get(tone), "fallback");
            final String hex = declared.get(tone);
            if (hex == null || hex.isBlank()) {
                parsed.put(tone, fallback);
                continue;
            }
            final TextColor colour = TextColor.fromHexString(hex.trim());
            if (colour == null) {
                problems.accept("the colour for " + tone + " is '" + hex + "', which is not a hex"
                        + " colour (it has to look like #8ba888); using the default "
                        + fallback.asHexString());
                parsed.put(tone, fallback);
            } else {
                parsed.put(tone, colour);
            }
        }
        return new ToneColours(parsed);
    }

    /** Returns the colour for {@code tone}, treating {@code null} as {@link Tone#NEUTRAL}. */
    TextColor of(final @Nullable Tone tone) {
        return Objects.requireNonNull(byTone.get(tone == null ? Tone.NEUTRAL : tone), "colour");
    }

    private static Map<Tone, TextColor> defaults() {
        final Map<Tone, TextColor> map = new EnumMap<>(Tone.class);
        map.put(Tone.GOOD, TextColor.fromHexString("#8ba888"));
        map.put(Tone.BAD, TextColor.fromHexString("#a8888b"));
        map.put(Tone.WARN, TextColor.fromHexString("#b08a4a"));
        map.put(Tone.NEUTRAL, TextColor.fromHexString("#c9c9c9"));
        map.put(Tone.MUTED, TextColor.fromHexString("#aaaaaa"));
        return map;
    }
}
