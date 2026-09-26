package eu.nordtal.s2.common.message;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.kyori.adventure.text.format.TextColor;
import org.jspecify.annotations.Nullable;

/**
 * The five {@link Tone} colours, parsed once and then answered from memory.
 *
 * This is {@code :common}'s half of the tone palette, the same split {@code FeedbackSounds} draws
 * for sounds: this class holds what each tone paints as a plain Adventure value and the one rule that
 * makes a wrong value harmless, while a platform module holds its own {@code @ConfigSpec} (a
 * {@code colours.yml}, one hex string per tone) and hands the declared values here to be turned into
 * something {@link Tones} can paint with. {@code :common} does not depend on jcore - see the
 * repository's own note on {@code :common}'s dependency block - so the spec itself cannot live here.
 *
 * <b>A bad hex value is never an exception on a player path</b>
 *
 * Exactly the same shape {@code FeedbackSounds} uses for a malformed sound key: a value that is not a
 * parseable hex colour is reported once, through the {@code problems} sink the caller passes in, and
 * the tone's default takes over. A colour that silently became "no colour at all" would be invisible
 * text - the whole reason this ticket exists - so refusing outright would trade one invisible failure
 * for another; correcting it and saying so in the log is the one response that is never worse than
 * doing nothing.
 */
public final class ToneColours {

    private static final Map<Tone, TextColor> DEFAULT_MAP = defaults();

    /**
     * The colour for each {@link Tone} when a deployment has not configured its own.
     *
     * A configured colour has to be a hex string and cannot name a constant, which is why
     * {@code #aaaaaa} is spelled out rather than written as
     * {@link net.kyori.adventure.text.format.NamedTextColor#GRAY}. {@code NEUTRAL}'s
     * {@code #c9c9c9} is a step lighter than {@code MUTED}'s {@code #aaaaaa} and short of white, so
     * an ordinary reply reads brighter than the supporting detail under it without becoming the
     * brightest thing on screen.
     */
    public static final ToneColours DEFAULTS = new ToneColours(DEFAULT_MAP);

    private final Map<Tone, TextColor> byTone;

    private ToneColours(final Map<Tone, TextColor> byTone) {
        this.byTone = byTone;
    }

    /**
     * Parses a module's {@code colours.yml}.
     *
     * @param declared a hex string per tone, as configured. A tone this map does not carry falls back
     *                 to {@link #DEFAULTS} without a complaint - that is what a freshly written file
     *                 with one tone not yet filled in looks like, and jcore itself never leaves a
     *                 declared key blank once the file has been written once
     * @param problems where a value that had to be replaced by its default is reported, once each. A
     *                 plugin passes {@code getLogger()::warning}
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

    /** What {@code null} is treated as too - see {@link Tones#paint}. */
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
