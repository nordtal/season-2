package eu.nordtal.s2.database.update;

import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jspecify.annotations.Nullable;

/** The SQL a run adds to the run inbox, whose state machine is the inbox's; {@link UpdateDirectory} is the API. */
@RegisterRowMapper(UpdateRequestMapper.class)
interface UpdateDao {

    /** Returns the oldest run that is pending or running, which is what refuses a new one. */
    @SqlQuery("""
            SELECT * FROM steward_inbox
            WHERE status IN ('PENDING', 'RUNNING')
            ORDER BY id
            LIMIT 1
            """)
    Optional<UpdateRequest> open();

    /** Returns the services a run is for, empty for a whole-network run and for no row. */
    @SqlQuery("SELECT jsonb_array_elements_text(payload -> 'services') FROM steward_inbox WHERE id = :id")
    List<String> services(@Bind("id") long id);

    @SqlQuery("SELECT * FROM steward_inbox WHERE id = :id")
    Optional<UpdateRequest> find(@Bind("id") long id);

    /**
     * Returns every request written after the one named, oldest first.
     *
     * @param id the last one already seen; {@code 0} for everything
     */
    @SqlQuery("SELECT * FROM steward_inbox WHERE id > :id ORDER BY id")
    java.util.List<UpdateRequest> since(@Bind("id") long id);

    /** Returns the most recent requests, newest first. */
    @SqlQuery("SELECT * FROM steward_inbox ORDER BY id DESC LIMIT :limit")
    java.util.List<UpdateRequest> recent(@Bind("limit") int limit);

    /** Returns the highest id in the table, or zero when it is empty. */
    @SqlQuery("SELECT coalesce(max(id), 0) FROM steward_inbox")
    long latestId();

    /** Returns every request that reached a terminal state in the last {@code seconds}. */
    @SqlQuery("""
            SELECT * FROM steward_inbox
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
            SELECT * FROM steward_inbox
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
                UPDATE steward_inbox
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
            WITH committed AS (
                UPDATE steward_inbox
                SET countdown_end = now()
                WHERE id = :id AND status = 'RUNNING'
                RETURNING id
            )
            SELECT committed.id, pg_notify('nordtal_update', '') AS notified FROM committed
            """)
    Optional<Long> commitCountdown(@Bind("id") long id);

    /**
     * Returns the earliest outage counting down: a pending row to its schedule, a running one to its countdown.
     *
     * The kind list is held against {@link UpdateKind#stopsServers()} by an integration test.
     */
    @SqlQuery("""
            SELECT * FROM steward_inbox
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
            SELECT * FROM steward_inbox
            WHERE status = 'RUNNING'
              AND (countdown_end IS NULL OR countdown_end <= now())
            ORDER BY id
            LIMIT 1
            """)
    Optional<UpdateRequest> running();

    /**
     * Withdraws the running countdown; {@code SKIP LOCKED} makes a cancel racing the commit answer empty.
     *
     * @param reason what goes into the outcome, naming who cancelled
     */
    @SqlQuery("""
            WITH cancellable AS (
                SELECT id
                FROM steward_inbox
                -- Exactly what countingDown() finds: the button says "Stop the countdown", and a
                -- countdown it could not stop would be worse than no button.
                WHERE kind IN ('RESTART', 'UPDATE', 'BACKUP', 'DOWN')
                  AND ((status = 'PENDING' AND scheduled_for > now())
                       OR (status = 'RUNNING' AND countdown_end > now()))
                ORDER BY coalesce(countdown_end, scheduled_for), id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            ),
            cancelled AS (
                UPDATE steward_inbox
                SET status = 'CANCELLED', finished = now(), outcome = to_jsonb(cast(:reason AS text))
                WHERE id IN (SELECT id FROM cancellable)
                RETURNING *
            )
            SELECT cancelled.*, pg_notify('nordtal_update', '') AS notified FROM cancelled
            """)
    Optional<UpdateRequest> cancelCountdown(@Bind("reason") String reason);

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
