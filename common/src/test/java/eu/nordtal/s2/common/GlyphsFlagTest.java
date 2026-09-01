package eu.nordtal.s2.common;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The one mapping in {@link Glyphs} that is a decision rather than a constant.
 *
 * <p>A flag beside a name says what to greet somebody in, so getting it wrong is not cosmetic - and
 * the fallbacks matter more than the hits: an unexpected locale, or one that has not been read yet,
 * has to render as a flag rather than as a missing-glyph box.
 */
class GlyphsFlagTest {

    @Test
    void theSeasonsThreeLanguagesGetTheirOwnFlags() {
        assertEquals(Glyphs.FLAG_GERMANY, Glyphs.flagFor(Locale.GERMAN));
        assertEquals(Glyphs.FLAG_GERMANY, Glyphs.flagFor(Locale.GERMANY));
        assertEquals(Glyphs.FLAG_NETHERLANDS, Glyphs.flagFor(Locale.of("nl")));
        assertEquals(Glyphs.FLAG_NETHERLANDS, Glyphs.flagFor(Locale.of("nl", "NL")));
    }

    @Test
    void englishIsBritishUnlessItSaysAmerican() {
        assertEquals(Glyphs.FLAG_UNITED_KINGDOM, Glyphs.flagFor(Locale.ENGLISH));
        assertEquals(Glyphs.FLAG_UNITED_KINGDOM, Glyphs.flagFor(Locale.UK));
        assertEquals(Glyphs.FLAG_UNITED_KINGDOM, Glyphs.flagFor(Locale.of("en", "AU")),
                "no flag is drawn for Australia, and the British one is the closer of the two");
        assertEquals(Glyphs.FLAG_UNITED_STATES, Glyphs.flagFor(Locale.US));
    }

    @Test
    void anythingElseIsTheNeutralFlagAndNeverAMissingGlyph() {
        assertEquals(Glyphs.FLAG_OTHER, Glyphs.flagFor(Locale.FRENCH));
        assertEquals(Glyphs.FLAG_OTHER, Glyphs.flagFor(Locale.of("pl")));
        assertEquals(Glyphs.FLAG_OTHER, Glyphs.flagFor(Locale.ROOT));
    }

    /** A player whose account link has not been read yet is in this state for about a second. */
    @Test
    void anUnknownLocaleIsTheNeutralFlag() {
        assertEquals(Glyphs.FLAG_OTHER, Glyphs.flagFor(null));
    }
}
