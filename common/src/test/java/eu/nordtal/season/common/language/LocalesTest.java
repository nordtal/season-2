package eu.nordtal.season.common.language;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import java.util.Optional;
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

    @Test
    void eachLanguageOfTheSeasonFliesTheFlagOfItsOwnCountry() {
        assertEquals(Optional.of("DE"), Locales.flagCountry(Locale.GERMAN));
        assertEquals(Optional.of("DE"), Locales.flagCountry(Locale.forLanguageTag("de-AT")));
        assertEquals(Optional.of("NL"), Locales.flagCountry(Locale.of("nl")));
    }

    @Test
    void englishFliesTheBritishFlagUnlessItSaysAmerican() {
        assertEquals(Optional.of("GB"), Locales.flagCountry(Locale.ENGLISH));
        assertEquals(Optional.of("GB"), Locales.flagCountry(Locale.of("en", "AU")));
        assertEquals(Optional.of("US"), Locales.flagCountry(Locale.US));
    }

    @Test
    void aLanguageWithoutAFlagOfItsOwnHasNone() {
        assertEquals(Optional.empty(), Locales.flagCountry(Locale.FRENCH));
        assertEquals(Optional.empty(), Locales.flagCountry(Locale.ROOT));
        assertEquals(Optional.empty(), Locales.flagCountry(null));
    }
}
