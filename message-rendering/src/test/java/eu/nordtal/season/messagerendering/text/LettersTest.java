package eu.nordtal.season.messagerendering.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class LettersTest {

    @Test
    void aWordWiderThanTheRoomBreaksIntoTheLongestStartsThatFit() {
        final String word = "w".repeat(2000);
        final List<String> lines =
                assertTimeoutPreemptively(Duration.ofSeconds(1), () -> Letters.wrapVisible(word, 100));

        assertEquals(word, String.join("", lines));
        lines.forEach(line -> assertTrue(Letters.visibleWidth(line) <= 100, line));
        lines.subList(0, lines.size() - 1)
                .forEach(line -> assertTrue(Letters.visibleWidth(line + "w") > 100, "the start is the longest"));
    }

    @Test
    void aLetterWiderThanTheRoomStillTakesALineOfItsOwn() {
        assertEquals(List.of("w", "w"), Letters.wrapVisible("ww", 0).subList(0, 2));
    }
}
