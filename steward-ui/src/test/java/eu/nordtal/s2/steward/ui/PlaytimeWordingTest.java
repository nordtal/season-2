package eu.nordtal.s2.steward.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.access.PlaytimeWording;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The journal says what the dialog asked for (steward/126).
 *
 * <p>Till: the play time dialog asks in days, hours and minutes rather than in decimal hours,
 * because a slipped decimal point is invisible in a field that accepts both. The list was pulled
 * across with it. This holds the last place a raw number of seconds reached a person - the
 * {@code SET_PLAYTIME} journal line, which the bot writes since Steward asks it to - to the same
 * wording, and holds the Java half of the formatting against the TypeScript half, which is a
 * second implementation of one rule and would otherwise drift in silence.</p>
 */
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
    @DisplayName("the three units, the empty ones left out, and a total of nothing still says 0 min")
    void theWordingIsTheOneTheInterfaceUses() {
        for (final long[] each : CASES) {
            assertEquals(EXPECTED.get((int) each[1]), PlaytimeWording.of(each[0]), each[0] + " seconds");
        }
    }

    @Test
    @DisplayName("seconds are dropped and never rounded up, so a value read back is the one written")
    void secondsAreDropped() {
        // The interface splits the same way. A Java half that rounded 3 659 up to "1 h 1 min" would
        // make the journal disagree with the column beside it for every value not on a minute.
        assertEquals("1 h", PlaytimeWording.of(3_659));
        assertEquals("0 min", PlaytimeWording.of(-1));
    }

    @Test
    @DisplayName("the journal line carries the wording and not a number of seconds")
    void theJournalDoesNotSaySeconds() throws IOException {
        // THE FAILURE THIS CATCHES: somebody edits the record(...) call back to ask.seconds because
        // it reads more precise. It is not more precise to a person - it is 111600, and the number
        // that is actually precise is in the log line, where nobody has to divide by 3600 to read
        // the journal.
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
