package eu.nordtal.s2.common.online;

import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The whole SQL surface of {@code online_count}, the same style as {@code MetricDao} and
 * {@code PlaytimeDao}.
 * <p>
 * Package-private: {@link OnlineDirectory} is the API, no consumer ever holds a {@code Jdbi} of
 * ours.
 * </p>
 * <h2>{@code OffsetDateTime}, not {@code Instant}</h2>
 * The same reason {@code MetricDao} gives: an {@code Instant} bound through JDBC is rendered in the
 * JVM's default zone and read back using the server's, which agree on this host and would agree in
 * almost every test - right up until the day they do not. An {@code OffsetDateTime} carries its own
 * offset onto the wire, so there is nothing for either side to assume.
 */
@RegisterRowMapper(OnlineCountMapper.class)
interface OnlineDao {

    /**
     * Replaces the row for every subject given, as one JDBC batch.
     *
     * <h2>{@code DO UPDATE}, not {@code DO NOTHING}</h2>
     * The opposite choice from {@code MetricDao#record}, and for the opposite reason: a measurement
     * at an instant is a fact and is never revised, but a player count is a snapshot of right now,
     * and the whole point of writing it again is to replace the stale one. {@code online_count} has
     * exactly one row per subject for this reason - see {@code V22__online_count.sql}.
     *
     * @param rows one per subject; empty is refused by JDBI itself, so {@link JdbiOnline#write}
     *             never calls this with nothing in it
     * @return one count per statement, always 1 - an UPSERT never fails to affect a row
     */
    @SqlBatch("""
            INSERT INTO online_count (subject, players, updated)
            VALUES (:subject, :players, :updated)
            ON CONFLICT (subject) DO UPDATE
                SET players = EXCLUDED.players,
                    updated = EXCLUDED.updated
            """)
    int[] write(@BindMethods Iterable<BoundCount> rows);

    /**
     * Every row there is, in no particular order that matters - the caller keys them by subject.
     */
    @SqlQuery("SELECT subject, players, updated FROM online_count")
    List<OnlineCount> current();

    /**
     * One subject's count with its instant already turned into an {@link OffsetDateTime} - see the
     * note on this interface for why that crossing matters.
     */
    record BoundCount(String subject, int players, OffsetDateTime updated) {
    }
}
