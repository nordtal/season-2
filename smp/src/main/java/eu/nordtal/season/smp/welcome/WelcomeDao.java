package eu.nordtal.season.smp.welcome;

import eu.nordtal.season.common.id.DiscordId;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/** The welcome flag of a player. A JDBI SqlObject. Never call it from the main thread. */
public interface WelcomeDao {

    /**
     * Takes the welcome flag of a player. The claim happens before anything is shown, so two sessions cannot both win.
     *
     * @return whether this call took the flag
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
