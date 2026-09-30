package eu.nordtal.s2.steward.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.database.access.PlaytimeWording;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The journal says what the dialog asked for, holding the Java formatting against the TypeScript one. */
class PlaytimeWordingTest {

    /** {@code format.ts}'s own tests pin these; the point here is that both halves agree. */
    private static final List<long[]> CASES = List.of(
            new long[] {86_400 + 6 * 3_600 + 30 * 60, 0},
            new long[] {2 * 86_400, 1},
            new long[] {6 * 3_600 + 30 * 60, 2},
            new long[] {30 * 60, 3},
            new long[] {0, 4},
            new long[] {59, 5},
            new long[] {3_659, 6});

    private static final List<String> EXPECTED =
            List.of("1 d 6 h 30 min", "2 d", "6 h 30 min", "30 min", "0 min", "0 min", "1 h");

    @Test
    void theWordingIsTheOneTheInterfaceUses() {
        for (final long[] each : CASES) {
            assertEquals(EXPECTED.get((int) each[1]), PlaytimeWording.of(each[0]), each[0] + " seconds");
        }
    }

    @Test
    void secondsAreDropped() {
        // Rounding 3 659 up to "1 h 1 min" would disagree with the column beside it in the journal.
        assertEquals("1 h", PlaytimeWording.of(3_659));
        assertEquals("0 min", PlaytimeWording.of(-1));
    }

    @Test
    void theJournalDoesNotSaySeconds() throws IOException {
        // Catches record(...) reverting to ask.seconds, precise to a machine but not to a reader.
        final Path source = repository()
                .resolve("discord-bot/src/main/java/eu/nordtal/s2/discordbot/discord/BotAccessEffects.java");
        final String text = Files.readString(source, StandardCharsets.UTF_8);

        final Matcher call = Pattern.compile("record\\(\"SET_PLAYTIME\".{0,1200}?\\);", Pattern.DOTALL)
                .matcher(text);
        assertTrue(call.find(), "the SET_PLAYTIME journal line is not where this test looks");

        final List<String> offending = new ArrayList<>();
        if (!call.group().contains("PlaytimeWording.of(seconds)")) {
            offending.add("the detail is not formatted with PlaytimeWording#of");
        }
        if (call.group().contains("\" seconds")) {
            offending.add("the detail still spells out a number of seconds");
        }
        assertEquals(List.of(), offending, call.group());
    }

    /** The same walk {@code EveryCalledPathIsRoutedTest} uses: up until settings.gradle.kts. */
    private static Path repository() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
            directory = directory.getParent();
        }
        assertTrue(
                directory != null, "no settings.gradle.kts above " + Path.of("").toAbsolutePath());
        return directory;
    }
}
