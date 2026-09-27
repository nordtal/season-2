package eu.nordtal.s2.proxy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The name in every MOTD carries the one brand colour.
 *
 * A text search, because the defaults are interface methods and the literal is what must hold.
 */
class BrandColourTest {

    private static final String SPEC = "proxy/src/main/java/eu/nordtal/s2/proxy/config/NetworkSpec.java";

    /** Every phase in {@code SeasonPhase}. */
    private static final int PHASES = 5;

    @Test
    void everyMotdUsesTheBrand() throws IOException {
        final String text = read();

        assertEquals(
                PHASES,
                occurrences(text, "return NORDTAL_BLUE"),
                "not every MOTD default opens with NORDTAL_BLUE. There is one mark, and the phase is"
                        + " what the second line says - a name that changes colour is four marks"
                        + " seen one at a time, which is what this file used to do.");
    }

    @Test
    void noPhaseColoursTheNameItself() throws IOException {
        final String text = read();

        assertFalse(
                text.contains("<gradient:"),
                "a gradient is back in the MOTDs. The five phases each had one before, and"
                        + " that is exactly the regression this test exists for; if a gradient is"
                        + " genuinely wanted, it belongs in NORDTAL_BLUE so all five share it.");

        final int marks = occurrences(text, "nordtal.eu</bold>");
        assertEquals(
                1,
                marks,
                "the brand name is written out " + marks + " times in this file. It belongs in"
                        + " NORDTAL_BLUE once; a second copy is a second place to forget.");
    }

    @Test
    void theBrandColourIsTheLogosBlue() throws IOException {
        final String text = read();

        assertTrue(
                text.contains("String NORDTAL_BLUE = \"<#4a63d8><bold>nordtal.eu</bold></#4a63d8>\";"),
                "NORDTAL_BLUE is no longer the value measured off resource-pack/src/pack.png and"
                        + " lightened for the server browser's near-black list. Changing it is"
                        + " allowed - changing it by accident is not, which is why the string is"
                        + " pinned here and explained there.");

        assertTrue(
                text.contains("#24357d"),
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
        assertTrue(
                Files.isRegularFile(path),
                SPEC + " no longer exists - if it moved, this path has"
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
