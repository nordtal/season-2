package eu.nordtal.s2.common.message;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Checks that every case of {@link Locales} ends in a locale, since it sits on the login path. */
class LocalesTest {

    @Test
    void parsesTheTagsSeasonTwoActuallyStores() {
        assertEquals(Locale.ENGLISH, Locales.parse("en"));
        assertEquals(Locale.GERMAN, Locales.parse("de"));
        assertEquals(Locale.GERMANY, Locales.parse("de-DE"));
    }

    @Test
    void degradesToEnglishInsteadOfThrowing() {
        assertEquals(Locale.ENGLISH, Locales.parse(null));
        assertEquals(Locale.ENGLISH, Locales.parse(""));
        assertEquals(Locale.ENGLISH, Locales.parse("   "));
        assertEquals(Locale.ENGLISH, Locales.parse("!!! not a language tag !!!"));
    }

    @Test
    void storesTheLanguageOnly() {
        assertEquals("de", Locales.tag(Locale.GERMANY));
        assertEquals("de", Locales.tag(Locale.forLanguageTag("de-AT")));
        assertEquals("en", Locales.tag(Locale.US));
        assertEquals("en", Locales.tag(null));
        assertEquals("en", Locales.tag(Locale.ROOT));
    }
}
