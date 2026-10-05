package eu.nordtal.season.settings.network;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.access.Prestige;
import eu.nordtal.season.spec.Specs;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The crest ladder the network ships, fourteen colours and thirteen hours, is held here.
 *
 * Colours are compared by distance, not inequality; the floor of 20 is about half the closest shipped pair.
 */
class PrestigeSpecDefaultsTest {

    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");

    /** The floor, in the units of {@link #distance}. */
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

    /** The two tier blocks carry the same keys, as the file's own header states. */
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
        // The same constructor the group's check runs, so a bad default fails here, not after a restart.
        assertDoesNotThrow(() -> new Prestige(NetworkSettings.prestigeHours(spec)));
    }

    /** The `@Key` values of a tier block, in `@Order`. */
    private static List<String> keysOf(final Class<?> block) {
        return Arrays.stream(block.getDeclaredMethods())
                .sorted(java.util.Comparator.comparingInt(
                        method -> method.getAnnotation(eu.nordtal.season.spec.annotation.Order.class)
                                .value()))
                .map(method -> method.getAnnotation(eu.nordtal.season.spec.annotation.Key.class)
                        .value())
                .toList();
    }

    /** The thirteen tiers plus the admin override, which has to stand apart from all of them. */
    private List<String> all() {
        final List<String> colours = new ArrayList<>(NetworkSettings.prestigeColours(spec));
        colours.add(spec.admin());
        return colours;
    }

    /**
     * The "redmean" approximation, a cheap weighted RGB distance that tracks what an eye does.
     *
     * Source: <a href="https://www.compuphase.com/cmetric.htm">compuphase</a>.
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
