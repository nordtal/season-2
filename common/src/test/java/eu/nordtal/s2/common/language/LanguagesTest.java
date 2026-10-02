package eu.nordtal.s2.common.language;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class LanguagesTest {

    @Test
    void theFallbackComesFirstThenTheOthersInOrder() {
        final Languages languages = new Languages(List.of("en", "de"));
        assertArrayEquals(new Locale[] {Locale.ENGLISH, Locale.GERMAN}, languages.locales());
        assertEquals(List.of(Locale.GERMAN), languages.others());
    }

    @Test
    void aListWithoutTheFallbackFirstIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Languages(List.of("de", "en")));
        assertThrows(IllegalArgumentException.class, () -> new Languages(List.of()));
    }

    @Test
    void aLanguageListedTwiceIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Languages(List.of("en", "de", "de")));
    }
}
