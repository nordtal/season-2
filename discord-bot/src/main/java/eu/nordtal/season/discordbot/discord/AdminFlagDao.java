package eu.nordtal.season.discordbot.discord;

import eu.nordtal.season.common.id.DiscordId;
import java.util.Optional;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

/** Reads {@code discord_user.admin} by {@code discord_id}. */
interface AdminFlagDao {

    @SqlQuery("SELECT admin FROM discord_user WHERE discord_id = :discordId")
    Optional<Boolean> isAdmin(@Bind("discordId") DiscordId discordId);

    /** Returns false for an account with no row. */
    static boolean admits(final Optional<Boolean> flag) {
        return flag.orElse(false);
    }
}
