package eu.nordtal.s2.common.message;

import java.util.Locale;
import java.util.UUID;

/**
 * Where {@link PlayerLocales} gets a language from when a player joins.
 * <p>
 * It is a one-method interface rather than a direct dependency on
 * {@code eu.nordtal.s2.common.access.AccessDirectory} for two reasons. The package that owns
 * messages must not depend on the package that owns access - {@code access} already depends on this
 * one, through {@link Locales} - and a component whose only collaborator is a lambda can be
 * exercised without a database for everything except the query itself.
 * </p>
 * <p>
 * The real implementation is always {@code accessDirectory::locale}: the language is
 * {@code discord_user.locale}, reached through {@code account_link}, and no role means English
 * ({@code docs/i18n.md}). The Minecraft client's own language setting is deliberately never
 * consulted, because Discord and the game showing different languages is exactly what having one
 * source avoids.
 * </p>
 */
@FunctionalInterface
public interface LocaleSource {

    /**
     * @param mcUuid the Minecraft account
     * @return that player's language; implementations should return {@link Locales#DEFAULT} rather
     *         than {@code null} for an unknown account
     */
    Locale localeOf(UUID mcUuid);
}
