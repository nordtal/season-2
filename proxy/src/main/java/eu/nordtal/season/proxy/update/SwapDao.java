package eu.nordtal.season.proxy.update;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/** The SQL of the proxy swap. {@link SwapStore} uses it. The DDL is in {@code :common}. */
interface SwapDao {

    /** Writes the seat of a player and replaces an older seat. */
    @SqlUpdate("""
            INSERT INTO proxy_swap_seat (player_uuid, server, recorded_at)
            VALUES (:player, :server, :now)
            ON CONFLICT (player_uuid) DO UPDATE
                SET server = EXCLUDED.server,
                    recorded_at = EXCLUDED.recorded_at
            """)
    int seat(@Bind("player") UUID player, @Bind("server") String server, @Bind("now") Instant now);

    /** Returns every seat and deletes them in one statement. */
    @SqlQuery("DELETE FROM proxy_swap_seat RETURNING player_uuid, server, recorded_at")
    @RegisterConstructorMapper(SwapStore.Seat.class)
    List<SwapStore.Seat> takeAllSeats();

    /** Writes the player count of the standby proxy and the time in one fixed row. */
    @SqlUpdate("""
            INSERT INTO proxy_standby_state (only_row, players, updated_at)
            VALUES (true, :players, :now)
            ON CONFLICT (only_row) DO UPDATE
                SET players = EXCLUDED.players,
                    updated_at = EXCLUDED.updated_at
            """)
    int reportStandby(@Bind("players") int players, @Bind("now") Instant now);
}
