package eu.nordtal.s2.discordbot.discord;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

import java.util.Optional;

/**
 * Reads {@code discord_user.admin} for one Discord account.
 *
 * <h2>Why this is here and not on {@code AccessDirectory}</h2>
 * {@code :common}'s access API answers questions about a <b>Minecraft</b> account, because that is
 * what the login path asks - {@code AccessState#admin()} arrives with everything else on the one
 * query a login makes. A Discord interaction has no UUID and may well belong to somebody who has
 * never linked one, so the same question has to be asked by {@code discord_id}. This module already
 * keeps that kind of lookup local ({@code ReconcileDao#localeOf} is the same shape and exists for
 * the same reason).
 *
 * <h2>The flag is only ever read here</h2>
 * It is <b>written</b> by {@link GuildState}, mirrored from the Discord admin role
 * ({@code docs/season-phases.md#how-an-admin-is-recognised}). Nothing in this module grants it, so
 * there is no {@code SqlUpdate} on this interface: an admin is appointed in Discord.
 */
interface AdminFlagDao {

    /**
     * @param discordId the account to ask about
     * @return {@code true} or {@code false} for an account the bot has a row for, and empty for one
     *         it has never written about - which is not the same thing as {@code false} and is left
     *         to the caller to fold, so that "unknown" cannot silently become "yes"
     */
    @SqlQuery("SELECT admin FROM discord_user WHERE discord_id = :discordId")
    Optional<Boolean> isAdmin(@Bind("discordId") String discordId);

    /**
     * The language this account chose, for a command that answers in it.
     *
     * <h2>Why it is on this interface rather than the row being read once</h2>
     * Two queries where one would do, and deliberately so: they are asked a handful of times a
     * season by an admin who is waiting on a deferred interaction, and a record with a constructor
     * mapper for the pair would be a mapper registered for one caller. If a third field ever joins
     * them, that is the moment to make it one row.
     *
     * @param discordId the account to ask about
     * @return the language tag, or empty for an account with no row or no language yet -
     *         {@code Locales.parse(null)} is English, which is the fallback everywhere
     */
    @SqlQuery("SELECT locale FROM discord_user WHERE discord_id = :discordId")
    Optional<String> localeOf(@Bind("discordId") String discordId);

    /**
     * Folds "the bot has never written about this account" into an answer, in one place.
     *
     * <p>{@link #isAdmin(String)} deliberately does not do it itself, so that "unknown" cannot
     * silently become "yes" somewhere. This is the only reading of it there is: an account with no
     * row is not an admin. It lives here rather than inside a command because it is a statement
     * about the column, and because {@code AdminFlagIntegrationTest} drives it against a real
     * database - which is where a nullable column and an absent row stop looking alike.</p>
     */
    static boolean admits(final Optional<Boolean> flag) {
        return flag.orElse(false);
    }
}
