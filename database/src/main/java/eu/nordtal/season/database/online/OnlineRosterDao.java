package eu.nordtal.season.database.online;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transaction;
import org.jspecify.annotations.Nullable;

/**
 * The SQL surface of {@code online_player}; {@link OnlineRoster} is the API.
 * Instants cross as {@code OffsetDateTime}, so neither the JVM's nor the server's zone is assumed.
 */
@RegisterRowMapper(OnlinePlayerMapper.class)
interface OnlineRosterDao {

    /**
     * Upserts everyone given, as one JDBC batch.
     *
     * @param rows one per connected player, never empty
     * @return one count per statement, always 1
     */
    @SqlBatch("""
            INSERT INTO online_player (mc_uuid, mc_name, subject, updated)
            VALUES (:uuid, :name, :subject, :updated)
            ON CONFLICT (mc_uuid) DO UPDATE
                SET mc_name = EXCLUDED.mc_name,
                    subject = EXCLUDED.subject,
                    updated = EXCLUDED.updated
            """)
    int[] upsert(@BindMethods Iterable<BoundPresence> rows);

    /**
     * Deletes every row older than this write, which is exactly the players it did not name.
     *
     * @param now the instant the accompanying {@link #upsert} stamped every row with
     * @return how many players were dropped
     */
    @SqlUpdate("DELETE FROM online_player WHERE updated < :now")
    int prune(@Bind("now") OffsetDateTime now);

    /**
     * Runs the upsert and the prune as one transaction, so a reader never sees two rosters at once.
     *
     * @param rows everyone connected; empty empties the table
     * @param now  the instant of this write
     */
    @Transaction
    default void replace(final List<BoundPresence> rows, final OffsetDateTime now) {
        if (!rows.isEmpty()) {
            upsert(rows);
        }
        prune(now);
    }

    /** Returns every row; the caller decides which of them are still worth believing. */
    @SqlQuery("SELECT mc_uuid, mc_name, subject, updated FROM online_player")
    List<OnlinePlayer> current();

    /** One presence with its instant as an {@link OffsetDateTime}. */
    record BoundPresence(UUID uuid, String name, @Nullable String subject, OffsetDateTime updated) {}
}
