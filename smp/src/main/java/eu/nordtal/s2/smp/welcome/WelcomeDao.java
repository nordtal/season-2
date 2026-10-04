package eu.nordtal.s2.smp.welcome;

import eu.nordtal.s2.common.id.DiscordId;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/** The one flag the season's welcome reads and takes; a JDBI SqlObject, never called from the main thread. */
public interface WelcomeDao {

    /**
     * Takes this player's one welcome, claimed before anything is shown so racing sessions cannot both win.
     *
     * @return whether this call is the one that took it
     */
    default boolean claimWelcome(final DiscordId discordId) {
        return claimWelcomeRow(discordId) > 0;
    }

    @SqlUpdate("""
            INSERT INTO smp_player (discord_id, welcome_shown)
            VALUES (:discordId, true)
            ON CONFLICT (discord_id) DO UPDATE
                SET welcome_shown = true, updated = now()
                WHERE NOT smp_player.welcome_shown
            """)
    int claimWelcomeRow(@Bind("discordId") DiscordId discordId);
}
