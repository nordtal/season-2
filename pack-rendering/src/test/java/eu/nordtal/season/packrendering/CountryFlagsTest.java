package eu.nordtal.season.packrendering;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Pins {@link CountryFlags}, whose fallbacks must render a flag rather than a missing-glyph box. */
class CountryFlagsTest {

    @Test
    void aCountryDrawsTheFlagItsGlyphNames() {
        assertEquals(Glyphs.FLAG_GERMANY, CountryFlags.of("flag-germany"));
        assertEquals(Glyphs.FLAG_AUSTRIA, CountryFlags.of("flag-austria"));
        assertEquals(Glyphs.FLAG_SWITZERLAND, CountryFlags.of("flag-switzerland"));
        assertEquals(Glyphs.FLAG_UNITED_STATES, CountryFlags.of("flag-united-states"));
    }

    /** A player whose identity has not been read yet, or who chose no country, is in this state. */
    @Test
    void noCountryIsTheNeutralFlag() {
        assertEquals(Glyphs.FLAG_OTHER, CountryFlags.of(null));
    }

    @Test
    void aGlyphThePackLacksIsTheNeutralFlagAndNeverAMissingGlyph() {
        assertEquals(Glyphs.FLAG_OTHER, CountryFlags.of("flag-france"));
        assertEquals(Glyphs.FLAG_OTHER, CountryFlags.of(""));
    }

    @Test
    void aGlyphThatIsNoFlagIsTheNeutralFlag() {
        final String notAFlag = Glyphs.named().keySet().stream()
                .filter(name -> !name.startsWith("flag-"))
                .findFirst()
                .orElseThrow();
        assertEquals(Glyphs.FLAG_OTHER, CountryFlags.of(notAFlag));
    }
}
