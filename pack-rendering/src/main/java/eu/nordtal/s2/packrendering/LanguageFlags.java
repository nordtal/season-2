package eu.nordtal.s2.packrendering;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Picks the flag glyph drawn beside a player's name for the language they read in. */
public final class LanguageFlags {

    private LanguageFlags() {}

    /**
     * Returns the flag for a language, and the neutral flag for anything unmapped, null included.
     *
     * {@code en} is British unless its country is the United States.
     */
    public static String of(final @Nullable Locale locale) {
        if (locale == null) {
            return Glyphs.FLAG_OTHER;
        }
        return switch (locale.getLanguage()) {
            case "de" -> Glyphs.FLAG_GERMANY;
            case "nl" -> Glyphs.FLAG_NETHERLANDS;
            case "en" -> "US".equals(locale.getCountry()) ? Glyphs.FLAG_UNITED_STATES : Glyphs.FLAG_UNITED_KINGDOM;
            default -> Glyphs.FLAG_OTHER;
        };
    }
}
