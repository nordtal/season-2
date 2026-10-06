package eu.nordtal.season.discordbot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.RepositoryRoot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The set of emojis every Discord message takes its marks from. */
class MarkTest {

    @Test
    void everyMarkHasItsOwnEmoji() {
        final Set<String> symbols = new HashSet<>();
        for (final Mark mark : Mark.values()) {
            assertTrue(symbols.add(mark.symbol()), mark + " repeats an emoji of another mark");
        }
    }

    @Test
    void aMarkIsNeverATextSymbolSoItCannotBeMixedWithOne() {
        for (final Mark mark : Mark.values()) {
            assertTrue(
                    mark.symbol().codePoints().anyMatch(MarkTest::emoji), mark + " is not an emoji: " + mark.symbol());
        }
    }

    @Test
    void aMarkStandsBeforeItsTextAndSeparatesNothing() {
        assertEquals("✅ Done", Mark.DONE.before("Done"));
    }

    @Test
    void noClassButMarkTypesAnEmoji() throws IOException {
        final Path sources = RepositoryRoot.resolve("discord-bot/src/main");
        final List<String> typed;
        try (Stream<Path> files = Files.walk(sources)) {
            typed = files.filter(Files::isRegularFile)
                    .filter(file ->
                            file.toString().endsWith(".java") || file.toString().endsWith(".properties"))
                    .filter(file -> !file.endsWith("Mark.java"))
                    .filter(file -> read(file).codePoints().anyMatch(MarkTest::emoji))
                    .map(RepositoryRoot::relative)
                    .toList();
        }

        assertTrue(typed.isEmpty(), "an emoji belongs to Mark, but these hold one: " + typed);
    }

    /** Whether a code point is an emoji or a variation selector, which is how Discord draws a mark. */
    private static boolean emoji(final int codePoint) {
        return codePoint >= 0x1F000
                || (codePoint >= 0x2300 && codePoint <= 0x23FF)
                || (codePoint >= 0x2600 && codePoint <= 0x27BF)
                || (codePoint >= 0x2B00 && codePoint <= 0x2BFF)
                || codePoint == 0xFE0F;
    }

    private static String read(final Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException failed) {
            throw new java.io.UncheckedIOException(failed);
        }
    }
}
