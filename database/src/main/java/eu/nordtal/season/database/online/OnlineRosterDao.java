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

/** The SQL of {@code online_player}. {@link OnlineRoster} is its API. Instants cross as {@code OffsetDateTime}. */
@RegisterRowMapper(OnlinePlayerMapper.class)
interface OnlineRosterDao {

    /**
     * Upserts everyone given as one JDBC batch.
     *
     * @param rows one per connected player, never empty
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
     * Deletes every row older than this write, which are the players it did not name.
     *
     * @param now the instant that {@link #upsert} stamped on every row
     * @return the players deleted
     */
    @SqlUpdate("DELETE FROM online_player WHERE updated < :now")
    int prune(@Bind("now") OffsetDateTime now);

    /**
     * Runs the upsert and the prune in one transaction, so a reader never sees two rosters.
     *
     * @param rows everyone connected. Empty empties the table
     */
    @Transaction
    default void replace(final List<BoundPresence> rows, final OffsetDateTime now) {
        if (!rows.isEmpty()) {
            upsert(rows);
        }
        prune(now);
    }

    @SqlQuery("SELECT mc_uuid, mc_name, subject, updated FROM online_player")
    List<OnlinePlayer> current();

    /** A presence with an {@link OffsetDateTime}. */
    record BoundPresence(UUID uuid, String name, @Nullable String subject, OffsetDateTime updated) {}
}
