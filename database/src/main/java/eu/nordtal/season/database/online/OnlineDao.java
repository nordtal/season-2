package eu.nordtal.season.database.online;

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
     * Replaces the row for every subject given, as one JDBC batch; unlike a metric, a count is meant to be overwritten.
     *
     * @param rows one per subject, never empty
     * @return one count per statement, always 1
     */
    @SqlBatch("""
            INSERT INTO online_count (subject, players, updated)
            VALUES (:subject, :players, :updated)
            ON CONFLICT (subject) DO UPDATE
                SET players = EXCLUDED.players,
                    updated = EXCLUDED.updated
            """)
    int[] write(@BindMethods Iterable<BoundCount> rows);

    /** Returns every row, in no order that matters. */
    @SqlQuery("SELECT subject, players, updated FROM online_count")
    List<OnlineCount> current();

    /** One subject's count with its instant as an {@link OffsetDateTime}. */
    record BoundCount(String subject, int players, OffsetDateTime updated) {}
}
