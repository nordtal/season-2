package eu.nordtal.s2.common.message;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Locales} sits on the login path - it turns a database column into the language a
 * disconnect screen is rendered in - so every one of these cases has to end in a locale rather
 * than an exception.
 */
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
