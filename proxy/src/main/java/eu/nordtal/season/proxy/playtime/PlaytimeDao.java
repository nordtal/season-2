package eu.nordtal.season.proxy.playtime;

import eu.nordtal.season.common.id.DiscordId;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/** The SQL of {@code player_playtime}. */
interface PlaytimeDao {

    /** Adds the seconds to the total of the player and returns 1. */
    @SqlUpdate("""
            INSERT INTO player_playtime (discord_id, seconds, updated)
            VALUES (:discordId, :seconds, now())
            ON CONFLICT (discord_id) DO UPDATE
                SET seconds = player_playtime.seconds + EXCLUDED.seconds,
                    updated = now()
            """)
    int add(@Bind("discordId") DiscordId discordId, @Bind("seconds") long seconds);
}
