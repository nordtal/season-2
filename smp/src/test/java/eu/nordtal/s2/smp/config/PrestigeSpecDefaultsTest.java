package eu.nordtal.s2.smp.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.config.spec.Specs;
import eu.nordtal.s2.smp.prestige.Prestige;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The crest ladder this plugin ships - fourteen colours and thirteen hours - is held here, and nowhere else.
 *
 * <b>Why this test exists.</b> A test that builds its own palette by hand never touches {@link PrestigeSpec} 's
 * shipped defaults, so a regression there - {@code tier13()} set to {@code tier01()} 's colour, or {@code NEUTRAL}
 * and {@code MUTED} both {@code GRAY} - would leave the build green with thirteen colours instead of two, the harder
 * version of the same problem.
 *
 * <b>Why a distance and not an inequality.</b> Two values being different is not the same as two colours being
 * tellable apart, and an inequality would pass happily on {@code #5fbfae} against {@code #5fbfaf}. The floor is
 * deliberately low, because these thirteen are a <i>gradient</i> and neighbouring tiers are supposed to be similar -
 * the measured closest pair today is tier-11 against tier-12 at 38.9, so a floor of 20 leaves nearly double the
 * margin and still catches a colour that was pasted twice.
 */
class PrestigeSpecDefaultsTest {

    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");

    /**
     * The floor, in the units of {@link #distance}.
     *
     * See the class javadoc for where it comes from: it is half of the closest pair actually shipped, not a number
     * picked to make this pass.
     */
    private static final double MINIMUM_DISTANCE = 20;

    private final PrestigeSpec spec = Specs.createDefault(PrestigeSpec.class);

    @Test
    void everyDefaultIsAParseableHexColour() {
        final List<String> wrong = new ArrayList<>();
        for (final String colour : all()) {
            if (!HEX.matcher(colour).matches()) {
                wrong.add(colour);
            }
        }
        assertTrue(
                wrong.isEmpty(),
                "a default that is not a hex colour is only noticed as a WARN in the log of a"
                        + " running server, where nobody reads it, and the tier silently wears the"
                        + " fallback instead: " + wrong);
    }

    @Test
    void noTwoDefaultsAreIndistinguishable() {
        // Only values this test can measure; a non-hex value is the other test's finding, not a raw exception here.
        final List<String> colours =
                all().stream().filter(c -> HEX.matcher(c).matches()).toList();
        final List<String> tooClose = new ArrayList<>();
        for (int i = 0; i < colours.size(); i++) {
            for (int j = i + 1; j < colours.size(); j++) {
                final double distance = distance(colours.get(i), colours.get(j));
                if (distance < MINIMUM_DISTANCE) {
                    tooClose.add(colours.get(i) + " and " + colours.get(j) + " are " + Math.round(distance) + " apart");
                }
            }
        }
        assertTrue(
                tooClose.isEmpty(),
                "two prestige colours that read as the same colour make two tiers indistinguishable"
                        + " in chat, in the tab list and above a player's head: " + tooClose);
    }

    /**
     * The contract the file's own header states, checked rather than trusted.
     *
     * The two blocks are one ladder written twice, and every reader of either walks {@code tier01..tier13}. A key added
     * to one and not the other is the failure that put these two lists in one file in the first place - tier 7's colour
     * against tier 8's hour - and it is invisible in both files and in the interface that draws them.
     */
    @Test
    void bothBlocksDeclareTheSameLadder() {
        assertEquals(
                keysOf(PrestigeSpec.TierHoursSpec.class),
                keysOf(PrestigeSpec.TierColoursSpec.class),
                "steward draws one row per tier by pairing these two blocks on their keys; a key in"
                        + " one and not the other silently drops a row or pairs the wrong two"
                        + " values");
    }

    @Test
    void theShippedHoursAreAValidLadder() {
        // The same constructor `Configs.prestige`'s validator runs, so a bad default fails here, not after a restart.
        assertDoesNotThrow(() -> new Prestige(Configs.declaredPrestigeHours(spec)));
    }

    /** The `@Key` values of a tier block, in `@Order`. */
    private static List<String> keysOf(final Class<?> block) {
        return Arrays.stream(block.getDeclaredMethods())
                .sorted(java.util.Comparator.comparingInt(
                        method -> method.getAnnotation(eu.nordtal.jcore.config.spec.annotation.Order.class)
                                .value()))
                .map(method -> method.getAnnotation(eu.nordtal.jcore.config.spec.annotation.Key.class)
                        .value())
                .toList();
    }

    /** The thirteen tiers plus the admin override, which has to stand apart from all of them. */
    private List<String> all() {
        final List<String> colours = new ArrayList<>(Configs.declaredPrestigeTiers(spec));
        colours.add(spec.admin());
        return colours;
    }

    /**
     * The "redmean" approximation - a cheap weighted RGB distance that tracks what an eye does.
     *
     * Better than a plain Euclidean one, and needs no colour-space conversion to compute. Source: the formula
     * documented at <a href="https://www.compuphase.com/cmetric.htm">compuphase</a>. Exact agreement with CIE Lab
     * is not the point here; catching two colours that are the same is.
     */
    private static double distance(final String first, final String second) {
        final int[] a = rgb(first);
        final int[] b = rgb(second);
        final double meanRed = (a[0] + b[0]) / 2.0;
        final double dr = a[0] - b[0];
        final double dg = a[1] - b[1];
        final double db = a[2] - b[2];
        return Math.sqrt((2 + meanRed / 256) * dr * dr + 4 * dg * dg + (2 + (255 - meanRed) / 256) * db * db);
    }

    private static int[] rgb(final String hex) {
        final String digits = hex.startsWith("#") ? hex.substring(1) : hex;
        return new int[] {
            Integer.parseInt(digits.substring(0, 2), 16),
            Integer.parseInt(digits.substring(2, 4), 16),
            Integer.parseInt(digits.substring(4, 6), 16),
        };
    }
}
