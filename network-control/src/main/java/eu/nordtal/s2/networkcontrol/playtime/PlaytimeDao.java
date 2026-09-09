package eu.nordtal.s2.networkcontrol.playtime;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * The whole SQL surface of the play-time counter, package-private because {@link PlaytimeStore} is
 * the API and no caller should hold a {@code Jdbi} of ours.
 * <p>
 * It lives here and not in {@code :common} because {@code player_playtime} is written by exactly one
 * process: only the proxy sees a session across servers, a backend sees just its own slice. The DDL
 * stays in {@code :common} with all the other DDL.
 * </p>
 */
interface PlaytimeDao {

    /**
     * Adds a slice of online time to a player's running total.
     *
     * <p>An addition in SQL rather than a read-modify-write in Java, so two racing flushes produce
     * the sum of both slices instead of losing one. {@code ON CONFLICT} makes the first flush of a
     * season and the ten-thousandth the same statement; the foreign key onto {@code discord_user}
     * holds, which is why this is only called with a Discord id the login query returned.</p>
     *
     * @param discordId the linked Discord account - the key of this table, not the Minecraft UUID
     * @param seconds   how many seconds to add; the caller never passes zero or less
     * @return 1
     */
    @SqlUpdate("""
            INSERT INTO player_playtime (discord_id, seconds, updated)
            VALUES (:discordId, :seconds, now())
            ON CONFLICT (discord_id) DO UPDATE
                SET seconds = player_playtime.seconds + EXCLUDED.seconds,
                    updated = now()
            """)
    int add(@Bind("discordId") String discordId, @Bind("seconds") long seconds);
}
