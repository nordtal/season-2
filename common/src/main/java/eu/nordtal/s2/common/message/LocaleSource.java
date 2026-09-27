package eu.nordtal.s2.common.message;

import java.util.Locale;
import java.util.UUID;

/**
 * Where {@link PlayerLocales} gets a language from when a player joins, in production {@code accessDirectory::locale}.
 *
 * The Minecraft client's own language is never consulted, so Discord and the game always agree.
 */
@FunctionalInterface
public interface LocaleSource {

    /** Returns that player's language, {@link Locales#DEFAULT} rather than null for an unknown account. */
    Locale localeOf(UUID mcUuid);
}
