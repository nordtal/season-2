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
}
