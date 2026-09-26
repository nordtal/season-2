package eu.nordtal.s2.common.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

/** Checks that tones are distinct, come from a configured palette, and fall back on a bad hex value. */
class TonesTest {

    @Test
    void neutralAndMutedArePaintedWithDifferentColours() {
        final Component neutral = Tones.paint(Component.text("x"), Tone.NEUTRAL, ToneColours.DEFAULTS);
        final Component muted = Tones.paint(Component.text("x"), Tone.MUTED, ToneColours.DEFAULTS);
        assertNotEquals(
                neutral.color(),
                muted.color(),
                "MUTED is supporting detail under a line that carries the message, and NEUTRAL is an"
                        + " ordinary reply - painting both the same grey makes a report of a few lines"
                        + " with detail under them read as one wall of identical text");
    }

    /** Checks that there are exactly five tones, since a sixth is a decision for the owner. */
    @Test
    void thereAreExactlyFiveTonesASixthIsADecisionForTheOwner() {
        assertEquals(5, Tone.values().length);
    }

    @Test
    void everyToneParsesToAColourEvenFromAnEmptyDeclaration() {
        final List<String> problems = new ArrayList<>();
        final ToneColours colours = ToneColours.parse(Map.of(), problems::add);
        for (final Tone tone : Tone.values()) {
            assertNotEquals(
                    null, Tones.paint(Component.text("x"), tone, colours).color());
        }
        assertEquals(
                List.of(),
                problems,
                "a tone absent from the declared map is not a mistake - it"
                        + " is what a config a caller built by hand looks like - so it must not be reported");
    }

    @Test
    void anInvalidHexValueFallsBackToTheDefaultAndIsReported() {
        final Map<Tone, String> declared = new EnumMap<>(Tone.class);
        declared.put(Tone.BAD, "not-a-colour");
        final List<String> problems = new ArrayList<>();

        final ToneColours colours = ToneColours.parse(declared, problems::add);

        final TextColor painted =
                Tones.paint(Component.text("x"), Tone.BAD, colours).color();
        final TextColor defaultBad =
                Tones.paint(Component.text("x"), Tone.BAD, ToneColours.DEFAULTS).color();
        assertEquals(
                defaultBad,
                painted,
                "a colour that cannot be parsed must fall back to the default rather than leaving the"
                        + " line uncoloured - an unpainted line is invisible in exactly the way this"
                        + " ticket exists to fix");
        assertEquals(1, problems.size(), "the bad value has to be reported exactly once");
        assertTrue(
                problems.get(0).contains("BAD") && problems.get(0).contains("not-a-colour"),
                "the report has to name which tone and which value were rejected, or nobody reading"
                        + " the log can find the line to fix: " + problems);
    }

    @Test
    void aBlankHexValueFallsBackToTheDefaultWithoutAComplaint() {
        final Map<Tone, String> declared = new EnumMap<>(Tone.class);
        declared.put(Tone.GOOD, "");
        final List<String> problems = new ArrayList<>();

        final ToneColours colours = ToneColours.parse(declared, problems::add);

        assertEquals(
                Tones.paint(Component.text("x"), Tone.GOOD, ToneColours.DEFAULTS)
                        .color(),
                Tones.paint(Component.text("x"), Tone.GOOD, colours).color());
        assertEquals(List.of(), problems);
    }
}
