package eu.nordtal.s2.smp.config;

import eu.nordtal.jcore.config.spec.Specs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fourteen colours this plugin ships are held here, and nowhere else (season-2-ingame/29).
 *
 * <p><b>Why this test exists.</b> season-2-ingame/23's own tests all build a palette by hand and
 * never touch {@link PrestigeColoursSpec}'s defaults, so the shipped values were covered by
 * nothing: {@code tier13()} was set to {@code tier01()}'s colour on 2026-09-16 and the build stayed
 * green. That is the exact bug season-2-ingame/22 existed to fix, where {@code NEUTRAL} and
 * {@code MUTED} were both {@code GRAY} and nobody noticed for a season - only here it is thirteen
 * colours instead of two, which is the harder version of the same problem.
 *
 * <p><b>Why a distance and not an inequality.</b> Two values being different is not the same as two
 * colours being tellable apart, and an inequality would pass happily on {@code #5fbfae} against
 * {@code #5fbfaf}. The floor is deliberately low, because these thirteen are a <i>gradient</i> and
 * neighbouring tiers are supposed to be similar - the measured closest pair today is tier-11 against
 * tier-12 at 38.9, so a floor of 20 leaves nearly double the margin and still catches a colour that
 * was pasted twice.
 */
class PrestigeColoursSpecDefaultsTest {

    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");

    /**
     * The floor, in the units of {@link #distance}. See the class javadoc for where it comes from:
     * it is half of the closest pair actually shipped, not a number picked to make this pass.
     */
    private static final double MINIMUM_DISTANCE = 20;

    private final PrestigeColoursSpec spec = Specs.createDefault(PrestigeColoursSpec.class);

    @Test
    @DisplayName("every shipped default is a hex colour this plugin can actually parse")
    void everyDefaultIsAParseableHexColour() {
        final List<String> wrong = new ArrayList<>();
        for (final String colour : all()) {
            if (!HEX.matcher(colour).matches()) {
                wrong.add(colour);
            }
        }
        assertTrue(wrong.isEmpty(),
                "a default that is not a hex colour is only noticed as a WARN in the log of a"
                        + " running server, where nobody reads it, and the tier silently wears the"
                        + " fallback instead: " + wrong);
    }

    @Test
    @DisplayName("no two shipped colours are close enough to read as the same colour")
    void noTwoDefaultsAreIndistinguishable() {
        // Only the values this test can actually measure. A value that is not a hex colour is the
        // other test's finding, and letting it crash this one too would replace a sentence naming
        // the offending pair with a NumberFormatException naming nothing.
        final List<String> colours = all().stream().filter(c -> HEX.matcher(c).matches()).toList();
        final List<String> tooClose = new ArrayList<>();
        for (int i = 0; i < colours.size(); i++) {
            for (int j = i + 1; j < colours.size(); j++) {
                final double distance = distance(colours.get(i), colours.get(j));
                if (distance < MINIMUM_DISTANCE) {
                    tooClose.add(colours.get(i) + " and " + colours.get(j)
                            + " are " + Math.round(distance) + " apart");
                }
            }
        }
        assertTrue(tooClose.isEmpty(),
                "two prestige colours that read as the same colour make two tiers indistinguishable"
                        + " in chat, in the tab list and above a player's head - which is what"
                        + " season-2-ingame/22 fixed for NEUTRAL and MUTED: " + tooClose);
    }

    /** The thirteen tiers plus the admin override, which has to stand apart from all of them. */
    private List<String> all() {
        final List<String> colours = new ArrayList<>(Configs.declaredPrestigeTiers(spec));
        colours.add(spec.admin());
        return colours;
    }

    /**
     * The "redmean" approximation - a cheap weighted RGB distance that tracks what an eye does far
     * better than a plain Euclidean one, and needs no colour-space conversion to compute. Source:
     * the formula documented at <a href="https://www.compuphase.com/cmetric.htm">compuphase</a>,
     * checked 2026-09-16. Exact agreement with CIE Lab is not the point here; catching two colours
     * that are the same is.
     */
    private static double distance(final String first, final String second) {
        final int[] a = rgb(first);
        final int[] b = rgb(second);
        final double meanRed = (a[0] + b[0]) / 2.0;
        final double dr = a[0] - b[0];
        final double dg = a[1] - b[1];
        final double db = a[2] - b[2];
        return Math.sqrt((2 + meanRed / 256) * dr * dr
                + 4 * dg * dg
                + (2 + (255 - meanRed) / 256) * db * db);
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
