package eu.nordtal.s2.proxy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The packaged server-list texts: one per phase, each opening with the one brand mark on a line of its own.
 * None carries a glyph, since the server list draws a text before any resource pack.
 */
class MotdTextTest {

    private static final List<String> PHASES = List.of("pre-launch", "pre-event", "start-event", "smp", "maintenance");

    private static final String BRAND = "<brand><bold>nordtal.eu</bold></brand><newline>";

    /** The five texts of one language, as the jar carries them. */
    private static List<String> all(final String language) {
        final Properties bundle = new Properties();
        try (Reader reader = new InputStreamReader(
                Objects.requireNonNull(
                        MotdTextTest.class.getResourceAsStream("/messages/proxy/" + language + ".properties")),
                StandardCharsets.UTF_8)) {
            bundle.load(reader);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return PHASES.stream()
                .map(phase -> Objects.requireNonNull(bundle.getProperty("motd." + phase), "motd." + phase))
                .toList();
    }

    @Test
    void everyPhaseGetsItsOwnTextRatherThanOneSharedLine() {
        for (final String language : List.of("en", "de")) {
            final Set<String> distinct = new HashSet<>(all(language));
            assertEquals(PHASES.size(), distinct.size(), "two phases share a text in " + language);
        }
    }

    @Test
    void everyTextOpensWithTheOneBrandMarkOnALineOfItsOwn() {
        // One mark, and the phase is what the second line says: a name that changes colour is five marks.
        for (final String language : List.of("en", "de")) {
            for (final String line : all(language)) {
                assertTrue(line.startsWith(BRAND), "opens without the mark: " + line);
                assertEquals(line.indexOf("nordtal.eu"), line.lastIndexOf("nordtal.eu"), "repeats the name: " + line);
                assertFalse(line.contains("Season 2") || line.contains("Staffel 2"), "spells the season out: " + line);
            }
        }
    }

    @Test
    void noTextCarriesAGlyph() {
        final List<String> offenders = new ArrayList<>();
        for (final String language : List.of("en", "de")) {
            for (final String line : all(language)) {
                line.codePoints()
                        .filter(MotdTextTest::isPrivateUse)
                        .forEach(codePoint -> offenders.add(
                                "U+" + Integer.toHexString(codePoint).toUpperCase(Locale.ROOT) + " in " + line));
                if (line.contains("<glyph:")) {
                    offenders.add("a glyph tag in " + line);
                }
            }
        }
        assertEquals(
                List.of(),
                offenders,
                "a private-use character in a MOTD is a box in the server browser: the client draws"
                        + " that list in its own font, before it has ever been offered the pack");
    }

    /** Both private-use areas, so a character pasted from an older file is still caught. */
    private static boolean isPrivateUse(final int codePoint) {
        return (codePoint >= 0xE000 && codePoint <= 0xF8FF)
                || (codePoint >= 0xF0000 && codePoint <= 0xFFFFD)
                || (codePoint >= 0x100000 && codePoint <= 0x10FFFD);
    }
}
