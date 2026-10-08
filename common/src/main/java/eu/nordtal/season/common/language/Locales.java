package eu.nordtal.season.common.language;

import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Turns a stored language tag into a {@link Locale} and back.
 *
 * Nothing here throws: a language column holding nonsense degrades to English rather than breaking a login.
 */
public final class Locales {

    /** The tag of {@link #DEFAULT}, the network's default language and the one every translation falls back to. */
    public static final String DEFAULT_TAG = "en";

    /** The default and the fallback for every user-visible string in season 2. */
    public static final Locale DEFAULT = Locale.of(DEFAULT_TAG);

    private Locales() {}

    /**
     * Parses a stored language tag such as {@code en} or {@code de}.
     *
     * @param tag the tag, may be {@code null} or blank
     * @return the matching locale, or {@link #DEFAULT} when the tag is missing or unparseable
     */
    public static Locale parse(final @Nullable String tag) {
        if (tag == null || tag.isBlank()) {
            return DEFAULT;
        }

        // forLanguageTag never throws; it returns Locale.ROOT ("") for anything it cannot read.
        final Locale locale = Locale.forLanguageTag(tag.trim());
        return locale.getLanguage().isEmpty() ? DEFAULT : locale;
    }

    /**
     * Returns the language-only tag stored in {@code discord_user.locale}.
     *
     * @param locale the locale, may be {@code null}
     * @return a lowercase two-letter language tag, {@code "en"} for {@code null}
     */
    public static String tag(final Locale locale) {
        if (locale == null || locale.getLanguage().isEmpty()) {
            return DEFAULT.getLanguage();
        }
        return locale.getLanguage().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns the country whose flag stands for a language, as its two upper-case letters.
     *
     * English is British unless the locale says American; a language without a flag of its own has none.
     */
    public static Optional<String> flagCountry(final @Nullable Locale locale) {
        if (locale == null) {
            return Optional.empty();
        }
        return switch (locale.getLanguage()) {
            case "de" -> Optional.of("DE");
            case "nl" -> Optional.of("NL");
            case "en" -> Optional.of("US".equals(locale.getCountry()) ? "US" : "GB");
            default -> Optional.empty();
        };
    }
}
