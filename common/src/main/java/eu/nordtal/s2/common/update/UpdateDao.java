package eu.nordtal.s2.common.update;

import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.Optional;

/**
 * The whole SQL surface of the updater's inbox, as a JDBI SqlObject interface - the same style as
 * {@code AccessDao} and {@code PhaseDao}.
 * <p>
 * Package-private on purpose: {@link UpdateDirectory} is the API, this is how it is implemented,
 * and no consumer should ever hold a {@code Jdbi} or a DAO of ours.
 * </p>
 */
@RegisterRowMapper(UpdateRequestMapper.class)
interface UpdateDao {

    /**
     * Writes a request and announces it, as <b>one statement</b>.
     *
     * <h2>The notification rides in the select list</h2>
     * Exactly as {@code PhaseDao#switchPhase} does it, and for the same reason: a notification is
     * then only ever emitted for a row that actually committed. It carries <b>no payload</b> - a
     * listener has to read the table anyway, because notifications are lost while a process is
     * disconnected, and a payload invites somebody to trust the notification as state. The channel
     * is {@code nordtal_update}, next to {@code nordtal_phase}.
     *
     * <h2>Why {@code make_interval} and not {@code interval 'N seconds'}</h2>
     * The delay has to come from a bind parameter, and string-concatenating one into an interval
     * literal is the shape this project does not write. {@code make_interval(secs => ...)} is also
     * the form that is <em>not</em> calendar arithmetic: V4 forbids {@code interval 'N days'} on a
     * {@code timestamptz} because days are evaluated in the session's time zone and change length
     * across a DST boundary. Seconds do not - a second is a second in every zone - so adding them
     * is exact wherever the writing JVM happens to think it is.
     *
     * @param kind         REPORT, APPLY or RESTART
     * @param source       DISCORD, GAME or CONSOLE
     * @param requestedBy  a Discord id, a Minecraft name, or {@code null}
     * @param delaySeconds how long from now the updater may act; zero for everything but a restart
     * @return the row as it was written
     */
    @SqlQuery("""
            WITH inserted AS (
                INSERT INTO update_request (kind, source, requested_by, not_before)
                VALUES (:kind, :source, :requestedBy,
                        now() + make_interval(secs => cast(:delaySeconds AS double precision)))
                RETURNING *
            )
            SELECT inserted.*, pg_notify('nordtal_update', '') AS notified
            FROM inserted
            """)
    UpdateRequest submit(@Bind("kind") String kind,
                         @Bind("source") String source,
                         @Bind("requestedBy") String requestedBy,
                         @Bind("delaySeconds") long delaySeconds);

    /**
     * Takes the oldest request that is due, and marks it {@code RUNNING} in the same statement.
     *
     * <h2>{@code FOR UPDATE SKIP LOCKED} is the whole concurrency story</h2>
     * Two updaters can exist for a moment - the long-running one and a one-shot {@code apply} an
     * operator started by hand. {@code SKIP LOCKED} means the second one takes the next row rather
     * than blocking on, or worse duplicating, the first one's. The jar swap itself is guarded
     * separately by an advisory lock; this only guards the row.
     *
     * <p>{@code not_before <= now()} is what makes a restart's countdown real: the row exists for
     * a minute before anything may claim it, which is the minute the proxy counts down and the
     * minute a cancel has to happen in.</p>
     *
     * @return the claimed request, or empty when there is nothing due
     */
    @SqlQuery("""
            WITH claimable AS (
                SELECT id
                FROM update_request
                WHERE status = 'PENDING'
                  AND not_before <= now()
                ORDER BY not_before, id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            )
            UPDATE update_request
            SET status = 'RUNNING', started = now()
            WHERE id IN (SELECT id FROM claimable)
            RETURNING *
            """)
    Optional<UpdateRequest> claimNext();

    /**
     * Writes the answer. Only a {@code RUNNING} row is finished, so a second updater cannot
     * overwrite an answer that is already there.
     *
     * @param id     the row
     * @param status DONE or FAILED
     * @param result the report, verbatim
     * @return the finished row, or empty when it was not {@code RUNNING} any more
     */
    @SqlQuery("""
            UPDATE update_request
            SET status = :status, finished = now(), result = :result
            WHERE id = :id AND status = 'RUNNING'
            RETURNING *
            """)
    Optional<UpdateRequest> finish(@Bind("id") long id,
                                   @Bind("status") String status,
                                   @Bind("result") String result);

    @SqlQuery("SELECT * FROM update_request WHERE id = :id")
    Optional<UpdateRequest> find(@Bind("id") long id);

    /**
     * The restart that has been asked for and has not happened yet - what network-control counts
     * down towards, and what a cancel withdraws.
     *
     * @return the pending restart, or empty. There is normally at most one; if a second was asked
     *         for, the earlier one is the one that will fire and therefore the one to show
     */
    @SqlQuery("""
            SELECT * FROM update_request
            WHERE status = 'PENDING' AND kind = 'RESTART'
            ORDER BY not_before, id
            LIMIT 1
            """)
    Optional<UpdateRequest> pendingRestart();

    /**
     * Withdraws the pending restart, if there still is one.
     *
     * <p>Guarded by {@code status = 'PENDING'} rather than by reading first and writing after: the
     * whole point is a race against an updater that may be claiming the very same row this
     * millisecond, and a check-then-act would lose it. An empty answer means the restart already
     * started - which is exactly the sentence the admin needs to be told.</p>
     *
     * @param reason what goes into {@code result}, naming who cancelled
     * @return the cancelled row, or empty when there was nothing left to cancel
     */
    @SqlQuery("""
            WITH cancellable AS (
                SELECT id
                FROM update_request
                WHERE status = 'PENDING' AND kind = 'RESTART'
                ORDER BY not_before, id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            )
            UPDATE update_request
            SET status = 'CANCELLED', finished = now(), result = :reason
            WHERE id IN (SELECT id FROM cancellable)
            RETURNING *
            """)
    Optional<UpdateRequest> cancelPendingRestart(@Bind("reason") String reason);

    /**
     * When the next pending request becomes due, whether or not that is now.
     *
     * <p>This is what keeps a countdown honest. The server otherwise sleeps for the poll interval,
     * and a restart whose minute runs out four seconds into a fifteen-second sleep would fire
     * eleven seconds after the counter reached zero in front of the players watching it.</p>
     *
     * @return the earliest {@code not_before} among pending rows, or empty when there are none
     */
    @SqlQuery("SELECT min(not_before) FROM update_request WHERE status = 'PENDING'")
    Optional<java.time.OffsetDateTime> nextDue();

    /**
     * Marks orphaned restarts as {@code DONE}.
     *
     * <p><b>This is inference, and it is worth being honest about.</b> A {@code RESTART} row left
     * {@code RUNNING} means an updater claimed it and then stopped existing - which is what a
     * successful redeploy does to this container, every time, by design. It could in principle
     * also be a crash on the line before the call. The reading chosen here is the overwhelmingly
     * likely one, and the row says which it is rather than claiming certainty.</p>
     *
     * @param result what to write into those rows
     * @return how many there were
     */
    @SqlUpdate("""
            UPDATE update_request
            SET status = 'DONE', finished = now(), result = :result
            WHERE status = 'RUNNING' AND kind = 'RESTART'
            """)
    int completeOrphanedRestarts(@Bind("result") String result);

    /**
     * Fails every other row still marked {@code RUNNING}.
     *
     * <p>Called once, at updater startup, after {@link #completeOrphanedRestarts(String)}. Nothing
     * is running them: the only process that claims a row is an updater, and this one has just
     * started. Without it a request killed mid-flight would sit {@code RUNNING} forever and every
     * surface reading it would show a spinner that never stops.</p>
     *
     * @param result what to write into those rows
     * @return how many there were
     */
    @SqlUpdate("""
            UPDATE update_request
            SET status = 'FAILED', finished = now(), result = :result
            WHERE status = 'RUNNING' AND kind <> 'RESTART'
            """)
    int failOrphans(@Bind("result") String result);
}
