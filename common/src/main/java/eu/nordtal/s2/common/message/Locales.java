package eu.nordtal.s2.common.message;

import java.util.Locale;

/**
 * The one place that turns a stored language tag into a {@link Locale} and back.
 * <p>
 * Season 2 is DE/EN; English is the default and the fallback everywhere. Nothing here ever
 * throws - a language column that somehow holds nonsense must degrade to English, not break a
 * login.
 * </p>
 */
public final class Locales {

    /** The default and the fallback for every user-visible string in season 2. */
    public static final Locale DEFAULT = Locale.ENGLISH;

    private Locales() {
    }

    /**
     * Parses a stored language tag such as {@code en} or {@code de}.
     *
     * @param tag the tag, may be {@code null} or blank
     * @return the matching locale, or {@link #DEFAULT} when the tag is missing or unparseable
     */
    public static Locale parse(final String tag) {
        if (tag == null || tag.isBlank()) {
            return DEFAULT;
        }

        // forLanguageTag never throws; it returns Locale.ROOT ("") for anything it cannot read.
        final Locale locale = Locale.forLanguageTag(tag.trim());
        return locale.getLanguage().isEmpty() ? DEFAULT : locale;
    }

    /**
     * The value stored in {@code discord_user.locale}: the language only, so {@code de-DE} and
     * {@code de-AT} are one language and one message bundle.
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
}
