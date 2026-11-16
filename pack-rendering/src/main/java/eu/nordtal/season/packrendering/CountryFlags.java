package eu.nordtal.season.packrendering;

import org.jspecify.annotations.Nullable;

/** Chooses the flag glyph drawn beside a player's name: the one of the country they chose. */
public final class CountryFlags {

    /** The prefix of every flag's pack name, so a country cannot draw another kind of glyph as its flag. */
    private static final String FLAG = "flag-";

    private CountryFlags() {}

    /**
     * Returns the glyph of the flag the country list names, or the neutral flag.
     *
     * @param flag the pack name of the country's flag glyph, null for a player without a country
     */
    public static String of(final @Nullable String flag) {
        if (flag == null || !flag.startsWith(FLAG)) {
            return Glyphs.FLAG_OTHER;
        }
        return Glyphs.named().getOrDefault(flag, Glyphs.FLAG_OTHER);
    }
}
