package eu.nordtal.season.proxy.playtime;

import eu.nordtal.season.common.id.DiscordId;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/** The whole SQL surface of the play-time counter; only the proxy writes {@code player_playtime}. */
interface PlaytimeDao {

    /**
     * Adds seconds to a player's total in SQL, so two racing flushes both count.
     *
     * @param discordId the key of this table, not the Minecraft UUID
     * @return 1
     */
    @SqlUpdate("""
            INSERT INTO player_playtime (discord_id, seconds, updated)
            VALUES (:discordId, :seconds, now())
            ON CONFLICT (discord_id) DO UPDATE
                SET seconds = player_playtime.seconds + EXCLUDED.seconds,
                    updated = now()
            """)
    int add(@Bind("discordId") DiscordId discordId, @Bind("seconds") long seconds);
}
