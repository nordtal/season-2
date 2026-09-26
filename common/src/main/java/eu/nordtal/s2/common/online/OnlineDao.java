package eu.nordtal.s2.common.online;

import java.time.OffsetDateTime;
import java.util.List;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

/**
 * The SQL surface of {@code online_count}; {@link OnlineDirectory} is the API.
 *
 * Instants cross as {@code OffsetDateTime}, so neither the JVM's nor the server's zone is assumed.
 */
@RegisterRowMapper(OnlineCountMapper.class)
interface OnlineDao {

    /**
     * Replaces the row for every subject given, as one JDBC batch.
     *
     * <b>{@code DO UPDATE}, not {@code DO NOTHING}</b>
     *
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

    /** One subject's count with its instant as an {@link OffsetDateTime}. */
    record BoundCount(String subject, int players, OffsetDateTime updated) {}
}
