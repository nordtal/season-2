package eu.nordtal.season.database.online;

import java.time.OffsetDateTime;
import java.util.List;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

/** The SQL of {@code online_count}. {@link OnlineDirectory} is its API. Instants cross as {@code OffsetDateTime}. */
@RegisterRowMapper(OnlineCountMapper.class)
interface OnlineDao {

    /**
     * Replaces the row of every subject given as one JDBC batch.
     *
     * @param rows one per subject, never empty
     */
    @SqlBatch("""
            INSERT INTO online_count (subject, players, updated)
            VALUES (:subject, :players, :updated)
            ON CONFLICT (subject) DO UPDATE
                SET players = EXCLUDED.players,
                    updated = EXCLUDED.updated
            """)
    int[] write(@BindMethods Iterable<BoundCount> rows);

    @SqlQuery("SELECT subject, players, updated FROM online_count")
    List<OnlineCount> current();

    /** A count with an {@link OffsetDateTime}. */
    record BoundCount(String subject, int players, OffsetDateTime updated) {}
}
