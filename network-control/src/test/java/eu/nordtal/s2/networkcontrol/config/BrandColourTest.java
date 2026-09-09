package eu.nordtal.s2.networkcontrol.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the name in the MOTD is one mark and not five.
 *
 * <h2>Why this is a text search</h2>
 * The same reason {@code AdminWatchWiringTest} is one: {@link NetworkSpec.MotdSpec}'s defaults are
 * {@code default} methods on an interface served by a reflective proxy, so there is no instance to
 * ask without loading a config, and what has to be protected is the <b>literal</b> somebody types
 * when they add a sixth phase.
 *
 * <p>It is not hypothetical. Until 2026-09-09 each of the five MOTDs coloured {@code nordtal.eu}
 * for itself: a light blue gradient before the start, orange during the hunger games, green on the
 * SMP, grey in maintenance. Every one of them was a reasonable choice on its own, and together they
 * meant the server browser showed four different marks depending on the day. The owner saw the
 * green one and named the rule: Nordtal is the dark blue of the logo, always.</p>
 *
 * <p>The value itself is measured rather than chosen - see the comment on
 * {@link NetworkSpec.MotdSpec#NORDTAL_BLUE}. This test pins the string exactly, so changing the
 * brand colour stays a deliberate edit in two places rather than a drift in one.</p>
 */
class BrandColourTest {

    private static final String SPEC =
            "network-control/src/main/java/eu/nordtal/s2/networkcontrol/config/NetworkSpec.java";

    /** Every phase in {@code SeasonPhase}. A sixth one has to appear here too. */
    private static final int PHASES = 5;

    @Test
    @DisplayName("every MOTD opens with the one brand colour")
    void everyMotdUsesTheBrand() throws IOException {
        final String text = read();

        assertEquals(PHASES, occurrences(text, "return NORDTAL_BLUE"),
                "not every MOTD default opens with NORDTAL_BLUE. There is one mark, and the phase is"
                        + " what the second line says - a name that changes colour is four marks"
                        + " seen one at a time, which is what this file did until 2026-09-09.");
    }

    @Test
    @DisplayName("no MOTD colours the name for itself again")
    void noPhaseColoursTheNameItself() throws IOException {
        final String text = read();

        assertFalse(text.contains("<gradient:"),
                "a gradient is back in the MOTDs. The five phases each had one until 2026-09-09 and"
                        + " that is exactly the regression this test exists for; if a gradient is"
                        + " genuinely wanted, it belongs in NORDTAL_BLUE so all five share it.");

        final int marks = occurrences(text, "nordtal.eu</bold>");
        assertEquals(1, marks,
                "the brand name is written out " + marks + " times in this file. It belongs in"
                        + " NORDTAL_BLUE once; a second copy is a second place to forget.");
    }

    @Test
    @DisplayName("the brand colour is the logo's blue, lightened for a dark list")
    void theBrandColourIsTheLogosBlue() throws IOException {
        final String text = read();

        assertTrue(text.contains("String NORDTAL_BLUE = \"<#4a63d8><bold>nordtal.eu</bold></#4a63d8>\";"),
                "NORDTAL_BLUE is no longer the value measured off resource-pack/src/pack.png and"
                        + " lightened for the server browser's near-black list. Changing it is"
                        + " allowed - changing it by accident is not, which is why the string is"
                        + " pinned here and explained there.");

        assertTrue(text.contains("#24357d"),
                "the comment no longer names #24357d, the logo's own blue. The lightened tone only"
                        + " makes sense next to the value it was lightened from - without it the"
                        + " next reader has a hex with no provenance.");
    }

    private static int occurrences(final String text, final String needle) {
        int count = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            count++;
            at = text.indexOf(needle, at + needle.length());
        }
        return count;
    }

    private static String read() throws IOException {
        final Path path = repositoryRoot().resolve(SPEC);
        assertTrue(Files.isRegularFile(path), SPEC + " no longer exists - if it moved, this path has"
                + " to move with it, because a missing file is a check that silently stops running");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** Anchors on the directory holding settings.gradle.kts, never on the nearest file by name. */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
            candidate = candidate.getParent();
        }
        assertTrue(candidate != null, "no settings.gradle.kts above the working directory");
        return candidate;
    }
}
