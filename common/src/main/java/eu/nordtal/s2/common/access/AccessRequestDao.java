package eu.nordtal.s2.common.access;

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
     * Writes a request and announces it, as <b>one statement</b>.
     *
     * <b>The notification rides in the select list</b>
     *
     * Exactly as {@code UpdateDao#submit} does it, and for the same reason: a notification then only
     * ever exists for a row that actually committed. It carries <b>no payload</b> - a listener has
     * to read the table anyway, because notifications are lost while a process is disconnected, and
     * a payload invites somebody to trust the notification as state.
     *
     * <b>{@code make_interval(secs => ...)} and not an interval literal</b>
     *
     * The patience has to come from a bind parameter, and V4 forbids calendar arithmetic on a
     * {@code timestamptz}: days are evaluated in the session's time zone and change length across a
     * DST boundary. Seconds do not, so adding them is exact wherever the writing JVM thinks it is.
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
     *
     * {@code FOR UPDATE SKIP LOCKED} keeps two bots from granting one request twice, and
     * {@code expires > now()} keeps an abandoned request from being carried out late.
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
     * Settle a claimed request.
     *
     * {@code AND status = 'RUNNING'} so that a bot which somehow settles a row twice writes
     * once. The second call updates nothing and says so through its row count, which
     * {@link AccessRequests#finish} logs rather than swallows.
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
     *
     * <b>Why anybody may run this, and why that is not a race</b>
     *
     * The bot cannot be the one that expires a row: the case this exists for is a bot that is not
     * running. So the sweep belongs to whoever looks - {@link AccessRequests#outcome} runs it
     * before it reads. {@code status = 'PENDING'} is the whole of the race: a row the bot claimed a
     * millisecond ago keeps its claim, and the sweep does nothing rather than declaring abandoned a
     * grant that is at that moment being carried out.
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

    /** One row, whatever state it is in. */
    @SqlQuery("SELECT * FROM access_request WHERE id = :id")
    Optional<AccessRequest> byId(@Bind("id") long id);

    /**
     * Every row still waiting, oldest first.
     *
     * What a reconnecting listener reads: a notification is delivered once and is lost while a
     * process is disconnected, so the first thing after {@code LISTEN} is a full read. The poll is
     * the guarantee; the notification only makes it immediate.
     */
    @SqlQuery("""
            SELECT *
            FROM access_request
            WHERE status = 'PENDING'
              AND expires > now()
            ORDER BY id
            """)
    List<AccessRequest> pending();

    /**
     * Deletes every settled request older than the retention window.
     *
     * One row per access change is not much, but nothing else ever deletes from this table and
     * "not much, for ever" is still for ever. Settled only - a pending row is work, not history.
     */
    @SqlUpdate("""
            DELETE FROM access_request
            WHERE status IN ('DONE', 'FAILED', 'EXPIRED')
              AND finished < now() - make_interval(secs => cast(:seconds AS double precision))
            """)
    int purge(@Bind("seconds") long seconds);
}
