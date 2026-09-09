package eu.nordtal.s2.discordbot.discord;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

import java.util.Optional;

/**
 * Reads {@code discord_user.admin} for one Discord account. {@code :common}'s access API asks by
 * Minecraft account, which a Discord interaction does not have, so the same question is asked by
 * {@code discord_id} here.
 *
 * <p>Read-only on purpose: {@link GuildState} writes the flag, mirrored from the Discord admin
 * role. An admin is appointed in Discord and nowhere else.</p>
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
     * The language this account chose, for a command that answers in it. Two queries where one
     * would do, deliberately: they are asked a handful of times a season by an admin waiting on a
     * deferred interaction.
     *
     * @param discordId the account to ask about
     * @return the language tag, or empty for an account with no row or no language yet -
     *         {@code Locales.parse(null)} is English, which is the fallback everywhere
     */
    @SqlQuery("SELECT locale FROM discord_user WHERE discord_id = :discordId")
    Optional<String> localeOf(@Bind("discordId") String discordId);

    /**
     * Folds "the bot has never written about this account" into an answer, in one place: an account
     * with no row is not an admin. {@link #isAdmin(String)} deliberately does not do it itself, so
     * "unknown" cannot silently become "yes" somewhere else.
     */
    static boolean admits(final Optional<Boolean> flag) {
        return flag.orElse(false);
    }
}
