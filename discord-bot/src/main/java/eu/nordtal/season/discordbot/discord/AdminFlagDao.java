package eu.nordtal.season.discordbot.discord;

import eu.nordtal.season.common.id.DiscordId;
import java.util.Optional;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

/** Reads {@code discord_user.admin} and the language by {@code discord_id}, never writing either. */
interface AdminFlagDao {

    /**
     * Returns the admin flag, or empty for an account with no row, which callers fold through {@link #admits}.
     *
     * @param discordId the account to ask about
     */
    @SqlQuery("SELECT admin FROM discord_user WHERE discord_id = :discordId")
    Optional<Boolean> isAdmin(@Bind("discordId") DiscordId discordId);

    /** Folds an account with no row into "not an admin", in one place. */
    static boolean admits(final Optional<Boolean> flag) {
        return flag.orElse(false);
    }
}
