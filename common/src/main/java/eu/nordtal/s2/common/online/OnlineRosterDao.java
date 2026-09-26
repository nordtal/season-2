package eu.nordtal.s2.common.online;

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

/**
 * The whole SQL surface of {@code online_player}, the same style as {@link OnlineDao}.
 *
 * Package-private: {@link OnlineRoster} is the API, no consumer ever holds a {@code Jdbi} of ours.
 *
 * <b>{@code OffsetDateTime}, not {@code Instant}</b>
 *
 * {@link OnlineDao}'s reason, unchanged: an {@code Instant} bound through JDBC is rendered in the
 * JVM's default zone and read back using the server's, which agree on this host and would agree in
 * almost every test - right up until the day they do not.
 */
@RegisterRowMapper(OnlinePlayerMapper.class)
interface OnlineRosterDao {

    /**
     * Upserts everyone given, as one JDBC batch.
     *
     * @param rows one per connected player; JDBI refuses an empty iterable, so {@link #replace} guards it
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
     * Deletes everyone this write did not touch.
     *
     * <b>By timestamp, and not by "not in this list"</b>
     *
     * Every row of one {@link #replace} carries the same instant, so "older than this write" is
     * exactly "was not in this write" - with no id list to build, no {@code NOT IN} that grows with
     * the player count, and nothing to get wrong when the list is empty. Strictly {@code <} and not
     * {@code <>}: a clock that stepped backwards must leave a newer row alone rather than delete a
     * player who is demonstrably still there.
     *
     * @param now the instant the accompanying {@link #upsert} stamped every row with
     * @return how many players were dropped, which is how many logged off since the last tick
     */
    @SqlUpdate("DELETE FROM online_player WHERE updated < :now")
    int prune(@Bind("now") OffsetDateTime now);

    /**
     * The upsert and the prune as one transaction, which is the only way the two are ever run.
     *
     * Together, because a reader between them would see the old roster and the new one at once -
     * a player counted twice under two names, or a leaver still sitting there next to a joiner. One
     * transaction makes the swap a single moment for anybody reading, which is the same promise
     * {@code /api/services} makes by reading the roster once per response.
     *
     * @param rows everyone connected, already stamped with {@code now}; empty is normal and means
     *             the prune below empties the table
     * @param now  the instant of this write
     */
    @Transaction
    default void replace(final List<BoundPresence> rows, final OffsetDateTime now) {
        if (!rows.isEmpty()) {
            upsert(rows);
        }
        prune(now);
    }

    /** Every row there is; the caller decides which of them are still worth believing. */
    @SqlQuery("SELECT mc_uuid, mc_name, subject, updated FROM online_player")
    List<OnlinePlayer> current();

    /**
     * One presence with its instant as an {@link OffsetDateTime}.
     *
     * @param subject may be {@code null}: the proxy has this player and no backend does yet
     */
    record BoundPresence(UUID uuid, String name, String subject, OffsetDateTime updated) {}
}
