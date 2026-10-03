package eu.nordtal.s2.packrendering;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Pins {@link LanguageFlags}, whose fallbacks must render a flag rather than a missing-glyph box. */
class LanguageFlagsTest {

    @Test
    void theSeasonsThreeLanguagesGetTheirOwnFlags() {
        assertEquals(Glyphs.FLAG_GERMANY, LanguageFlags.of(Locale.GERMAN));
        assertEquals(Glyphs.FLAG_GERMANY, LanguageFlags.of(Locale.GERMANY));
        assertEquals(Glyphs.FLAG_NETHERLANDS, LanguageFlags.of(Locale.of("nl")));
        assertEquals(Glyphs.FLAG_NETHERLANDS, LanguageFlags.of(Locale.of("nl", "NL")));
    }

    @Test
    void englishIsBritishUnlessItSaysAmerican() {
        assertEquals(Glyphs.FLAG_UNITED_KINGDOM, LanguageFlags.of(Locale.ENGLISH));
        assertEquals(Glyphs.FLAG_UNITED_KINGDOM, LanguageFlags.of(Locale.UK));
        assertEquals(
                Glyphs.FLAG_UNITED_KINGDOM,
                LanguageFlags.of(Locale.of("en", "AU")),
                "no flag is drawn for Australia, and the British one is the closer of the two");
        assertEquals(Glyphs.FLAG_UNITED_STATES, LanguageFlags.of(Locale.US));
    }

    @Test
    void anythingElseIsTheNeutralFlagAndNeverAMissingGlyph() {
        assertEquals(Glyphs.FLAG_OTHER, LanguageFlags.of(Locale.FRENCH));
        assertEquals(Glyphs.FLAG_OTHER, LanguageFlags.of(Locale.of("pl")));
        assertEquals(Glyphs.FLAG_OTHER, LanguageFlags.of(Locale.ROOT));
    }

    /** A player whose account link has not been read yet is in this state for about a second. */
    @Test
    void anUnknownLocaleIsTheNeutralFlag() {
        assertEquals(Glyphs.FLAG_OTHER, LanguageFlags.of(null));
    }
}
