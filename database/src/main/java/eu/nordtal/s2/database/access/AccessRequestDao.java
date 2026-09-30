package eu.nordtal.s2.database.access;

import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jspecify.annotations.Nullable;

/** The SQL surface of the bot's access inbox; {@link AccessRequests} is the API. */
@RegisterRowMapper(AccessRequestMapper.class)
interface AccessRequestDao {

    /**
     * Writes a request and announces it in one statement, so a notification only exists for a committed row.
     * It carries no payload, and patience is added in seconds because day arithmetic on a {@code timestamptz} shifts
     * across DST.
     */
    @SqlQuery("""
            WITH inserted AS (
                INSERT INTO access_request (kind, subject, argument, source, requested_by, expires)
                VALUES (:kind, :subject, :argument, :source, :requestedBy,
                        now() + make_interval(secs => cast(:patienceSeconds AS double precision)))
                RETURNING *
            )
            SELECT inserted.*, pg_notify('nordtal_access', '') AS notified
            FROM inserted
            """)
    AccessRequest submit(
            @Bind("kind") String kind,
            @Bind("subject") String subject,
            @Bind("argument") @Nullable String argument,
            @Bind("source") String source,
            @Bind("requestedBy") @Nullable String requestedBy,
            @Bind("patienceSeconds") long patienceSeconds);

    /**
     * Claims the oldest unexpired request and marks it {@code RUNNING} in the same statement.
     * {@code FOR UPDATE SKIP LOCKED} keeps two bots from granting one request twice.
     */
    @SqlQuery("""
            UPDATE access_request
            SET status  = 'RUNNING',
                started = now()
            WHERE id = (SELECT id
                        FROM access_request
                        WHERE status = 'PENDING'
                          AND expires > now()
                        ORDER BY id
                            FOR UPDATE SKIP LOCKED
                        LIMIT 1)
            RETURNING *
            """)
    Optional<AccessRequest> claim();

    /**
     * Settles a claimed request.
     * The {@code RUNNING} guard makes a second call update nothing, which its row count reports.
     */
    @SqlUpdate("""
            UPDATE access_request
            SET status   = :status,
                finished = now(),
                result   = :result
            WHERE id = :id
              AND status = 'RUNNING'
            """)
    int finish(@Bind("id") long id, @Bind("status") String status, @Bind("result") String result);

    /**
     * Gives up on every pending row whose patience has run out.
     * Anybody may run it, since the bot may be down; a row the bot already claimed is left alone.
     *
     * @return how many rows this call expired
     */
    @SqlUpdate("""
            UPDATE access_request
            SET status   = 'EXPIRED',
                finished = now()
            WHERE status = 'PENDING'
              AND expires <= now()
            """)
    int expireDue();

    /** Returns one row, whatever state it is in. */
    @SqlQuery("SELECT * FROM access_request WHERE id = :id")
    Optional<AccessRequest> byId(@Bind("id") long id);

    /**
     * Returns every row still waiting, oldest first.
     * A reconnecting listener reads it in full, because a notification is lost while disconnected.
     */
    @SqlQuery("""
            SELECT *
            FROM access_request
            WHERE status = 'PENDING'
              AND expires > now()
            ORDER BY id
            """)
    List<AccessRequest> pending();

    /** Deletes every settled request older than the retention window; a pending row is work, not history. */
    @SqlUpdate("""
            DELETE FROM access_request
            WHERE status IN ('DONE', 'FAILED', 'EXPIRED')
              AND finished < now() - make_interval(secs => cast(:seconds AS double precision))
            """)
    int purge(@Bind("seconds") long seconds);
}
