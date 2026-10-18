package eu.nordtal.season.packrendering;

import eu.nordtal.season.common.language.Locales;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Chooses the flag glyph drawn beside a player's name. */
public final class LanguageFlags {

    private LanguageFlags() {}

    /** Returns the glyph of the flag {@link Locales#flagCountry} names, or the neutral flag. */
    public static String of(final @Nullable Locale locale) {
        return switch (Locales.flagCountry(locale).orElse("")) {
            case "DE" -> Glyphs.FLAG_GERMANY;
            case "NL" -> Glyphs.FLAG_NETHERLANDS;
            case "GB" -> Glyphs.FLAG_UNITED_KINGDOM;
            case "US" -> Glyphs.FLAG_UNITED_STATES;
            default -> Glyphs.FLAG_OTHER;
        };
    }
}
