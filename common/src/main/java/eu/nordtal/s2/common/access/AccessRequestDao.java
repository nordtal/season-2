package eu.nordtal.s2.common.access;

import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;

/**
 * The whole SQL surface of the bot's access inbox, as a JDBI SqlObject interface - the same style as
 * {@code UpdateDao} and {@code CommandRequestDao}.
 *
 * <p>Package-private on purpose: {@link AccessRequests} is the API, this is how it is implemented,
 * and no consumer should ever hold a {@code Jdbi} or a DAO of ours.</p>
 */
@RegisterRowMapper(AccessRequestMapper.class)
interface AccessRequestDao {

    /**
     * Writes a request and announces it, as <b>one statement</b>.
     *
     * <h2>The notification rides in the select list</h2>
     * Exactly as {@code UpdateDao#submit} does it, and for the same reason: a notification then only
     * ever exists for a row that actually committed. It carries <b>no payload</b> - a listener has
     * to read the table anyway, because notifications are lost while a process is disconnected, and
     * a payload invites somebody to trust the notification as state.
     *
     * <h2>{@code make_interval(secs => ...)} and not an interval literal</h2>
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
    AccessRequest submit(@Bind("kind") String kind,
                         @Bind("subject") String subject,
                         @Bind("argument") String argument,
                         @Bind("source") String source,
                         @Bind("requestedBy") String requestedBy,
                         @Bind("patienceSeconds") long patienceSeconds);

    /**
     * Takes the oldest request that has not expired, and marks it {@code RUNNING} in the same
     * statement.
     *
     * <h2>{@code FOR UPDATE SKIP LOCKED} is the whole concurrency story</h2>
     * There is meant to be one bot, and "meant to be" is not a guarantee - a rolling restart briefly
     * runs two. A claim in two statements would hand one grant to both of them and give somebody
     * twice the days they paid for. {@code SKIP LOCKED} makes the loser take the next row rather
     * than block behind the winner.
     *
     * <h2>{@code expires > now()} is the executing side's half of the boundary</h2>
     * A row past its patience has been given up on by whoever was watching it. Carrying it out
     * anyway is how somebody's access gets granted twice, once long after they were told it had
     * not been. Refusing to claim it closes the window from this end.
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
     * <p>{@code AND status = 'RUNNING'} so that a bot which somehow settles a row twice writes
     * once. The second call updates nothing and says so through its row count, which
     * {@link AccessRequests#finish} logs rather than swallows.</p>
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
     * <h2>Why anybody may run this, and why that is not a race</h2>
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
     * <p>What a reconnecting listener reads: a notification is delivered once and is lost while a
     * process is disconnected, so the first thing after {@code LISTEN} is a full read. The poll is
     * the guarantee; the notification only makes it immediate.</p>
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
     * <p>One row per access change is not much, but nothing else ever deletes from this table and
     * "not much, for ever" is still for ever. Settled only - a pending row is work, not history.</p>
     */
    @SqlUpdate("""
            DELETE FROM access_request
            WHERE status IN ('DONE', 'FAILED', 'EXPIRED')
              AND finished < now() - make_interval(secs => cast(:seconds AS double precision))
            """)
    int purge(@Bind("seconds") long seconds);
}
