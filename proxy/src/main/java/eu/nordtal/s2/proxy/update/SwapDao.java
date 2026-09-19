package eu.nordtal.s2.proxy.update;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The whole SQL surface of the proxy swap, package-private for the reason {@code PlaytimeDao} gives:
 * {@link SwapStore} is the API and no caller should hold a {@code Jdbi} of ours.
 *
 * <p>Both tables are written by the proxy and by nothing else - {@code proxy_swap_seat} by the live
 * one, {@code proxy_standby_state} by the standby - so the SQL lives here rather than in
 * {@code :common}. The DDL stays in {@code :common} with all the other DDL.</p>
 */
interface SwapDao {

    /**
     * Writes where one player is standing, replacing whatever was there.
     *
     * <p>{@code ON CONFLICT} rather than a delete and an insert: a player transferred twice in one
     * run - it takes a failed swap and a retry - has one seat, and it is the newer one.</p>
     */
    @SqlUpdate("""
            INSERT INTO proxy_swap_seat (player_uuid, server, recorded_at)
            VALUES (:player, :server, :now)
            ON CONFLICT (player_uuid) DO UPDATE
                SET server = EXCLUDED.server,
                    recorded_at = EXCLUDED.recorded_at
            """)
    int seat(@Bind("player") UUID player, @Bind("server") String server, @Bind("now") Instant now);

    /**
     * Takes every seat there is, in one statement, and leaves the table empty.
     *
     * <h2>Why all of them, once, at startup - and never one per login</h2>
     * The seats of a swap are read by the proxy that comes back from it, and there is exactly one
     * moment when that proxy wants them: the moment it starts. Reading them per login would put a
     * round trip on a path this network keeps pinned to one, for an answer that is empty on every
     * login of the season except the handful in the twenty seconds after a swap.
     *
     * <p>{@code DELETE ... RETURNING} rather than a select: the table is emptied by the same
     * statement that reads it, so "valid only for the length of this run" is a property of the data
     * and not a promise somebody has to keep. That includes the rows nobody came back for - a
     * player who closed their client mid-swap - which is why there is no separate sweep. Whether a
     * row is still worth acting on is decided by its age, in Java, on the way into memory.</p>
     */
    @SqlQuery("DELETE FROM proxy_swap_seat RETURNING player_uuid, server, recorded_at")
    @RegisterConstructorMapper(SwapStore.Seat.class)
    List<SwapStore.Seat> takeAllSeats();

    /**
     * The standby's own heartbeat: how many players it is holding, and when it last said so.
     *
     * <p>One row, fixed by the primary key - see the migration. {@code ON CONFLICT} is therefore
     * not a nicety but the whole write: every heartbeat after the first is the update.</p>
     */
    @SqlUpdate("""
            INSERT INTO proxy_standby_state (only_row, players, updated_at)
            VALUES (true, :players, :now)
            ON CONFLICT (only_row) DO UPDATE
                SET players = EXCLUDED.players,
                    updated_at = EXCLUDED.updated_at
            """)
    int reportStandby(@Bind("players") int players, @Bind("now") Instant now);
}
