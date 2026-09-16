package eu.nordtal.s2.common.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * season-2-ingame/22: {@code NEUTRAL} and {@code MUTED} have to be distinguishable colours, every
 * tone has to come from a configured palette rather than a hardcoded constant, and a bad hex value
 * has to fall back to the default rather than vanish.
 */
class TonesTest {

    @Test
    @DisplayName("NEUTRAL and MUTED are painted with different colours")
    void neutralAndMutedDiffer() {
        final Component neutral = Tones.paint(Component.text("x"), Tone.NEUTRAL, ToneColours.DEFAULTS);
        final Component muted = Tones.paint(Component.text("x"), Tone.MUTED, ToneColours.DEFAULTS);
        assertNotEquals(neutral.color(), muted.color(),
                "MUTED is supporting detail under a line that carries the message, and NEUTRAL is an"
                        + " ordinary reply - painting both the same grey makes a report of a few lines"
                        + " with detail under them read as one wall of identical text");
    }

    /**
     * {@code Tone.values().length == 5} guards the same thing {@code SoundVocabularyTest} guards for
     * {@code Feedback}: a sixth tone is a decision for the owner, not something that should compile
     * silently. {@link ToneColours#parse} iterates {@code Tone.values()}, so a sixth tone is answered
     * automatically here - the guard that actually matters is in each plugin's own adapter, where the
     * exhaustive switch from a spec's five accessors to a {@code Map<Tone, String>} stops compiling
     * until somebody says what the new tone's key is called.
     */
    @Test
    @DisplayName("there are exactly five tones - a sixth is a decision for the owner")
    void exactlyFiveTones() {
        assertEquals(5, Tone.values().length);
    }

    @Test
    @DisplayName("every tone parses to a colour, even from an empty declaration")
    void everyToneHasADefault() {
        final List<String> problems = new ArrayList<>();
        final ToneColours colours = ToneColours.parse(Map.of(), problems::add);
        for (final Tone tone : Tone.values()) {
            assertNotEquals(null, Tones.paint(Component.text("x"), tone, colours).color());
        }
        assertEquals(List.of(), problems, "a tone absent from the declared map is not a mistake - it"
                + " is what a config a caller built by hand looks like - so it must not be reported");
    }

    @Test
    @DisplayName("an invalid hex value falls back to the default and is reported")
    void invalidHexFallsBackAndReports() {
        final Map<Tone, String> declared = new EnumMap<>(Tone.class);
        declared.put(Tone.BAD, "not-a-colour");
        final List<String> problems = new ArrayList<>();

        final ToneColours colours = ToneColours.parse(declared, problems::add);

        final TextColor painted = Tones.paint(Component.text("x"), Tone.BAD, colours).color();
        final TextColor defaultBad =
                Tones.paint(Component.text("x"), Tone.BAD, ToneColours.DEFAULTS).color();
        assertEquals(defaultBad, painted,
                "a colour that cannot be parsed must fall back to the default rather than leaving the"
                        + " line uncoloured - an unpainted line is invisible in exactly the way this"
                        + " ticket exists to fix");
        assertEquals(1, problems.size(), "the bad value has to be reported exactly once");
        assertTrue(problems.get(0).contains("BAD") && problems.get(0).contains("not-a-colour"),
                "the report has to name which tone and which value were rejected, or nobody reading"
                        + " the log can find the line to fix: " + problems);
    }

    @Test
    @DisplayName("a blank hex value falls back to the default without a complaint")
    void blankHexFallsBackSilently() {
        final Map<Tone, String> declared = new EnumMap<>(Tone.class);
        declared.put(Tone.GOOD, "");
        final List<String> problems = new ArrayList<>();

        final ToneColours colours = ToneColours.parse(declared, problems::add);

        assertEquals(Tones.paint(Component.text("x"), Tone.GOOD, ToneColours.DEFAULTS).color(),
                Tones.paint(Component.text("x"), Tone.GOOD, colours).color());
        assertEquals(List.of(), problems);
    }
}
