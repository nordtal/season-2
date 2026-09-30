package eu.nordtal.s2.database.update;

import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jspecify.annotations.Nullable;

/** The SQL surface of steward-worker's inbox; {@link UpdateDirectory} is the API. */
@RegisterRowMapper(UpdateRequestMapper.class)
interface UpdateDao {

    /**
     * Writes a request and announces it on {@code nordtal_update} in one statement.
     *
     * @param actorId      the Discord id of a person, {@code null} for anyone else
     * @param delaySeconds how long from now steward-worker may claim it
     * @param scope        comma-separated compose service names, or {@code null} for the whole network
     */
    @SqlQuery("""
            WITH inserted AS (
                INSERT INTO update_request (kind, actor_kind, actor_id, scheduled_for, scope)
                VALUES (:kind, :actorKind, :actorId,
                        now() + make_interval(secs => cast(:delaySeconds AS double precision)),
                        :scope)
                RETURNING *
            )
            SELECT inserted.*, pg_notify('nordtal_update', '') AS notified
            FROM inserted
            """)
    UpdateRequest submit(
            @Bind("kind") String kind,
            @Bind("actorKind") String actorKind,
            @Bind("actorId") @Nullable String actorId,
            @Bind("delaySeconds") long delaySeconds,
            @Bind("scope") @Nullable String scope);

    /** Returns the oldest run that is pending or running, which is what refuses a new one. */
    @SqlQuery("""
            SELECT * FROM update_request
            WHERE status IN ('PENDING', 'RUNNING')
            ORDER BY id
            LIMIT 1
            """)
    Optional<UpdateRequest> open();

    /** Returns the {@code scope} column, or {@code null} for a whole-network run and for no row. */
    @SqlQuery("SELECT scope FROM update_request WHERE id = :id")
    @Nullable
    String scope(@Bind("id") long id);

    /** Claims the oldest due request and marks it {@code RUNNING}, skipping rows another worker has locked. */
    @SqlQuery("""
            WITH claimable AS (
                SELECT id
                FROM update_request
                WHERE status = 'PENDING'
                  AND scheduled_for <= now()
                ORDER BY scheduled_for, id
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
     * Writes the answer, only onto a {@code RUNNING} row.
     *
     * @param status DONE or FAILED
     * @return the finished row, or empty when it was not {@code RUNNING} any more
     */
    @SqlQuery("""
            UPDATE update_request
            SET status = :status, finished = now(), result = :result
            WHERE id = :id AND status = 'RUNNING'
            RETURNING *
            """)
    Optional<UpdateRequest> finish(@Bind("id") long id, @Bind("status") String status, @Bind("result") String result);

    /** Rewrites a running request's report and leaves its status alone, so a late write cannot undo a cancel. */
    @SqlUpdate("""
            UPDATE update_request
            SET result = :result
            WHERE id = :id AND status = 'RUNNING'
            """)
    int progress(@Bind("id") long id, @Bind("result") String result);

    /**
     * Puts a running request back into the inbox, report and all, for the next worker to claim.
     *
     * A row settled meanwhile stays settled. {@code scheduled_for} stays in the past, so no countdown shows.
     */
    @SqlUpdate("""
            UPDATE update_request
            SET status = 'PENDING', started = NULL, result = :result
            WHERE id = :id AND status = 'RUNNING'
            """)
    int handOver(@Bind("id") long id, @Bind("result") String result);

    @SqlQuery("SELECT * FROM update_request WHERE id = :id")
    Optional<UpdateRequest> find(@Bind("id") long id);

    /**
     * Returns every request written after the one named, oldest first.
     *
     * @param id the last one already seen; {@code 0} for everything
     */
    @SqlQuery("SELECT * FROM update_request WHERE id > :id ORDER BY id")
    java.util.List<UpdateRequest> since(@Bind("id") long id);

    /** Returns the most recent requests, newest first. */
    @SqlQuery("SELECT * FROM update_request ORDER BY id DESC LIMIT :limit")
    java.util.List<UpdateRequest> recent(@Bind("limit") int limit);

    /** Returns the highest id in the table, or zero when it is empty. */
    @SqlQuery("SELECT coalesce(max(id), 0) FROM update_request")
    long latestId();

    /** Returns every request that reached a terminal state in the last {@code seconds}. */
    @SqlQuery("""
            SELECT * FROM update_request
            WHERE finished IS NOT NULL
              AND finished > now() - make_interval(secs => cast(:seconds AS double precision))
            ORDER BY id
            """)
    java.util.List<UpdateRequest> finishedWithin(@Bind("seconds") long seconds);

    /**
     * Returns every {@code BACKUP} that reached {@code DONE} inside the window, newest first.
     * The caller parses each report and stops at the first that proves a file was saved.
     *
     * @param seconds how far back to look, from the database's clock
     */
    @SqlQuery("""
            SELECT * FROM update_request
            WHERE kind = 'BACKUP'
              AND status = 'DONE'
              AND finished IS NOT NULL
              AND finished > now() - make_interval(secs => cast(:seconds AS double precision))
            ORDER BY finished DESC, id DESC
            """)
    java.util.List<UpdateRequest> backupsDoneWithin(@Bind("seconds") long seconds);

    /**
     * Starts and announces the countdown on a claimed request, only while it is still {@code RUNNING}.
     *
     * @param moving the services the run stops, which the proxy evacuates once the countdown runs out
     * @return the row with its {@code countdown_end}, or empty when it was withdrawn
     */
    @SqlQuery("""
            WITH updated AS (
                UPDATE update_request
                SET countdown_end = now() + make_interval(secs => cast(:seconds AS double precision)),
                    moving = :moving
                WHERE id = :id AND status = 'RUNNING'
                RETURNING *
            )
            SELECT updated.*, pg_notify('nordtal_update', '') AS notified
            FROM updated
            """)
    Optional<UpdateRequest> startCountdown(
            @Bind("id") long id, @Bind("seconds") long seconds, @Bind("moving") String[] moving);

    /**
     * Ends the countdown atomically; the row lock decides the race with a cancel at zero.
     *
     * @return the id when the run may go ahead, empty when it was cancelled
     */
    @SqlQuery("""
            UPDATE update_request
            SET countdown_end = now()
            WHERE id = :id AND status = 'RUNNING'
            RETURNING id
            """)
    Optional<Long> commitCountdown(@Bind("id") long id);

    /**
     * Returns the earliest outage counting down: a pending row to its schedule, a running one to its countdown.
     *
     * The kind list is held against {@link UpdateKind#stopsServers()} by an integration test.
     */
    @SqlQuery("""
            SELECT * FROM update_request
            WHERE kind IN ('RESTART', 'UPDATE', 'BACKUP', 'DOWN')
              AND ((status = 'PENDING' AND scheduled_for > now())
                   OR (status = 'RUNNING' AND countdown_end > now()))
            ORDER BY coalesce(countdown_end, scheduled_for), id
            LIMIT 1
            """)
    Optional<UpdateRequest> countingDown();

    /**
     * Returns the run that is happening right now: claimed and not counting down, servers going down.
     *
     * Any kind counts, since a backup stops the same servers as an update.
     */
    @SqlQuery("""
            SELECT * FROM update_request
            WHERE status = 'RUNNING'
              AND (countdown_end IS NULL OR countdown_end <= now())
            ORDER BY id
            LIMIT 1
            """)
    Optional<UpdateRequest> running();

    /**
     * Withdraws the running countdown; {@code SKIP LOCKED} makes a cancel racing the commit answer empty.
     *
     * @param reason what goes into {@code result}, naming who cancelled
     */
    @SqlQuery("""
            WITH cancellable AS (
                SELECT id
                FROM update_request
                -- Exactly what countingDown() finds: the button says "Stop the countdown", and a
                -- countdown it could not stop would be worse than no button.
                WHERE kind IN ('RESTART', 'UPDATE', 'BACKUP', 'DOWN')
                  AND ((status = 'PENDING' AND scheduled_for > now())
                       OR (status = 'RUNNING' AND countdown_end > now()))
                ORDER BY coalesce(countdown_end, scheduled_for), id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            )
            UPDATE update_request
            SET status = 'CANCELLED', finished = now(), result = :reason
            WHERE id IN (SELECT id FROM cancellable)
            RETURNING *
            """)
    Optional<UpdateRequest> cancelCountdown(@Bind("reason") String reason);

    /** Returns the earliest {@code scheduled_for} among pending rows, or empty when there are none. */
    @SqlQuery("SELECT min(scheduled_for) FROM update_request WHERE status = 'PENDING'")
    Optional<java.time.OffsetDateTime> nextDue();

    /**
     * Fails every row still marked {@code RUNNING}; called once at worker startup.
     *
     * @return how many there were
     */
    @SqlUpdate("""
            UPDATE update_request
            SET status = 'FAILED', finished = now(), result = :result
            WHERE status = 'RUNNING'
            """)
    int failOrphans(@Bind("result") String result);

    /** Returns every service being held down, newest first. */
    @SqlQuery("SELECT * FROM service_hold ORDER BY since DESC, service")
    @RegisterRowMapper(ServiceHoldMapper.class)
    List<ServiceHold> holds();

    /** Writes the hold, or refreshes the one already there so the newest press says who holds it. */
    @SqlUpdate("""
            INSERT INTO service_hold (service, actor_kind, actor_id, request_id)
            VALUES (:service, :actorKind, :actorId, :requestId)
            ON CONFLICT (service) DO UPDATE
                SET since = now(), actor_kind = EXCLUDED.actor_kind, actor_id = EXCLUDED.actor_id,
                    request_id = EXCLUDED.request_id
            """)
    void hold(
            @Bind("service") String service,
            @Bind("actorKind") String actorKind,
            @Bind("actorId") @Nullable String actorId,
            @Bind("requestId") @Nullable Long requestId);

    /** Returns how many rows went away; zero when it was not being held. */
    @SqlUpdate("DELETE FROM service_hold WHERE service = :service")
    int release(@Bind("service") String service);
}
