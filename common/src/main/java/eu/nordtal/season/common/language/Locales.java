package eu.nordtal.season.common.language;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Converts a stored language tag to a {@link Locale} and back. It never throws. */
public final class Locales {

    /** The tag of {@link #DEFAULT}. */
    public static final String DEFAULT_TAG = "en";

    public static final Locale DEFAULT = Locale.of(DEFAULT_TAG);

    private Locales() {}

    /** Returns the locale of a stored tag, or {@link #DEFAULT} if the tag is null, blank or unreadable. */
    public static Locale parse(final @Nullable String tag) {
        if (tag == null || tag.isBlank()) {
            return DEFAULT;
        }

        final Locale locale = Locale.forLanguageTag(tag.trim());
        return locale.getLanguage().isEmpty() ? DEFAULT : locale;
    }

    /** Returns the lower-case language tag that {@code discord_user.locale} stores. */
    public static String tag(final Locale locale) {
        if (locale == null || locale.getLanguage().isEmpty()) {
            return DEFAULT.getLanguage();
        }
        return locale.getLanguage().toLowerCase(Locale.ROOT);
    }

    /** Returns the capitalised name of a language in itself, such as Deutsch, or its tag when the JDK has none. */
    public static String ownName(final Locale locale) {
        final String own = locale.getDisplayLanguage(locale);
        return own.isEmpty() || own.equals(locale.getLanguage())
                ? tag(locale)
                : own.substring(0, 1).toUpperCase(locale) + own.substring(1);
    }
}
