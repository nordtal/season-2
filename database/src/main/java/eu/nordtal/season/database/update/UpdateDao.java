package eu.nordtal.season.database.update;

import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.Notifies;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jspecify.annotations.Nullable;

/** The SQL that a run adds to the run inbox. {@link UpdateDirectory} is its API. */
@RegisterRowMapper(UpdateRequestMapper.class)
@Notifies(Channel.UPDATE)
interface UpdateDao {

    /** Selects a run with the services it stops, which {@link UpdateRequestMapper} reads. */
    String RUN = """
            SELECT id, kind, status, actor_kind, actor_id, requested, scheduled_for, countdown_end, started, finished,
                   stage, array(SELECT s.service FROM run_service s WHERE s.run = r.id AND s.moving ORDER BY s.service)
                              AS moving
            FROM steward_inbox r
            """;

    /** Returns the oldest pending or running run, which refuses a new one. */
    @SqlQuery(RUN + """
            WHERE status IN ('PENDING', 'RUNNING')
            ORDER BY id
            LIMIT 1
            """)
    Optional<UpdateRequest> open();

    /** Returns the services a run's request names, empty for a whole-network run and for no row. */
    @SqlQuery("SELECT service FROM run_service WHERE run = :id AND named ORDER BY service")
    List<String> scope(@Bind("id") long id);

    /**
     * Returns the services a run's payload names, for a schema before the rows of its request.
     * shortcut: reads the payload, which the next release drops with this query.
     */
    @SqlQuery("SELECT jsonb_array_elements_text(payload -> 'services') FROM steward_inbox WHERE id = :id")
    List<String> payloadScope(@Bind("id") long id);

    /** Returns the kind and payload of a run, for a schema before the rows of its request. */
    @SqlQuery("SELECT kind, cast(payload AS text) AS payload FROM steward_inbox WHERE id = :id")
    @RegisterConstructorMapper(Payload.class)
    Optional<Payload> payload(@Bind("id") long id);

    /** A run's kind and the JSON its request was before the rows of its kind. */
    record Payload(String kind, String payload) {}

    /** Names the services a new request is for. Steward may insert them, but not update them. */
    @SqlUpdate("""
            INSERT INTO run_service (run, service, moving, named)
            SELECT :run, service, false, true FROM unnest(cast(:services AS text[])) AS named (service)
            """)
    void named(@Bind("run") long run, @Bind("services") String[] services);

    @SqlQuery(RUN + "WHERE id = :id")
    Optional<UpdateRequest> find(@Bind("id") long id);

    /**
     * Returns every request after the one named, oldest first.
     *
     * @param id the last one already seen. 0 for everything
     */
    @SqlQuery(RUN + "WHERE id > :id ORDER BY id")
    java.util.List<UpdateRequest> since(@Bind("id") long id);

    @SqlQuery(RUN + "ORDER BY id DESC LIMIT :limit")
    java.util.List<UpdateRequest> recent(@Bind("limit") int limit);

    @SqlQuery("SELECT coalesce(max(id), 0) FROM steward_inbox")
    long latestId();

    /** Returns every request that reached a terminal state in the last {@code seconds}. */
    @SqlQuery(RUN + """
            WHERE finished IS NOT NULL
              AND finished > now() - make_interval(secs => cast(:seconds AS double precision))
            ORDER BY id
            """)
    java.util.List<UpdateRequest> finishedWithin(@Bind("seconds") long seconds);

    /**
     * Returns the newest {@code BACKUP} that reached {@code DONE} in the window and saved an archive.
     *
     * @param seconds how far back to look, by the database's clock
     */
    @SqlQuery(RUN + """
            WHERE kind = 'BACKUP'
              AND status = 'DONE'
              AND finished IS NOT NULL
              AND finished > now() - make_interval(secs => cast(:seconds AS double precision))
              AND EXISTS (SELECT 1 FROM backup_archive a JOIN run_step s ON s.id = a.saved_by WHERE s.run = r.id)
            ORDER BY finished DESC, id DESC
            LIMIT 1
            """)
    Optional<UpdateRequest> lastSavingBackup(@Bind("seconds") long seconds);

    /**
     * Starts and announces the countdown on a claimed {@code RUNNING} request, with its moving services.
     * The services are marked in the same statement, so the proxy reads them once it wakes.
     *
     * @param moving the services the run stops, which the proxy moves players off when the countdown ends
     * @return the id, or empty if it was withdrawn
     */
    @SqlQuery("""
            WITH updated AS (
                UPDATE steward_inbox
                SET countdown_end = now() + make_interval(secs => cast(:seconds AS double precision)),
                    moving = :moving
                WHERE id = :id AND status = 'RUNNING'
                RETURNING id
            ),
            marked AS (
                INSERT INTO run_service (run, service, moving)
                SELECT updated.id, stopped.service, true
                FROM updated, unnest(cast(:moving AS text[])) AS stopped (service)
                ON CONFLICT (run, service) DO UPDATE SET moving = true
            )
            SELECT updated.id, pg_notify(:channel, '') AS notified FROM updated
            """)
    Optional<Long> startCountdown(@Bind("id") long id, @Bind("seconds") long seconds, @Bind("moving") String[] moving);

    /**
     * Writes and announces the stage of a running request.
     *
     * @return the id, or empty if it is not running
     */
    @SqlQuery("""
            WITH staged AS (
                UPDATE steward_inbox SET stage = :stage WHERE id = :id AND status = 'RUNNING' RETURNING id
            )
            SELECT staged.id, pg_notify(:channel, '') AS notified FROM staged
            """)
    Optional<Long> progress(@Bind("id") long id, @Bind("stage") String stage);

    /** Writes and announces the stage of a run that steward-agent records already settled. */
    @SqlQuery("""
            WITH staged AS (UPDATE steward_inbox SET stage = :stage WHERE id = :id RETURNING id)
            SELECT staged.id, pg_notify(:channel, '') AS notified FROM staged
            """)
    Optional<Long> recorded(@Bind("id") long id, @Bind("stage") String stage);

    /**
     * Ends the countdown atomically. The row lock decides a race with a cancel at zero.
     *
     * @return the id if the run may go ahead, empty if it was cancelled
     */
    @SqlQuery("""
            WITH committed AS (
                UPDATE steward_inbox
                SET countdown_end = now()
                WHERE id = :id AND status = 'RUNNING'
                RETURNING id
            )
            SELECT committed.id, pg_notify(:channel, '') AS notified FROM committed
            """)
    Optional<Long> commitCountdown(@Bind("id") long id);

    /**
     * Returns the earliest outage counting down: a pending row to its schedule, a running one to its countdown.
     * An integration test holds the kind list against {@link UpdateKind#stopsServers()}.
     */
    @SqlQuery(RUN + """
            WHERE kind <> 'START'
              AND ((status = 'PENDING' AND scheduled_for > now())
                   OR (status = 'RUNNING' AND countdown_end > now()))
            ORDER BY coalesce(countdown_end, scheduled_for), id
            LIMIT 1
            """)
    Optional<UpdateRequest> countingDown();

    /**
     * Returns the run that is happening now: claimed, not counting down, servers going down.
     * Any kind counts, as a backup stops the same servers as an update.
     */
    @SqlQuery(RUN + """
            WHERE status = 'RUNNING'
              AND (countdown_end IS NULL OR countdown_end <= now())
            ORDER BY id
            LIMIT 1
            """)
    Optional<UpdateRequest> running();

    /**
     * Withdraws the running countdown. {@code SKIP LOCKED} makes a cancel that races the commit answer empty.
     * Its stage becomes {@code CANCELLED}. The journal line says who cancelled.
     */
    @SqlQuery("""
            WITH cancellable AS (
                SELECT id
                FROM steward_inbox
                -- Exactly what countingDown() finds: the button says "Stop the countdown", and a
                -- countdown it could not stop would be worse than no button.
                WHERE kind <> 'START'
                  AND ((status = 'PENDING' AND scheduled_for > now())
                       OR (status = 'RUNNING' AND countdown_end > now()))
                ORDER BY coalesce(countdown_end, scheduled_for), id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            ),
            cancelled AS (
                UPDATE steward_inbox
                SET status = 'CANCELLED', finished = now(), stage = 'CANCELLED'
                WHERE id IN (SELECT id FROM cancellable)
                RETURNING id
            )
            SELECT cancelled.id, pg_notify(:channel, '') AS notified FROM cancelled
            """)
    Optional<Long> cancelCountdown();

    /** Names the container that runs a running request. Zero rows if it is not running. */
    @SqlUpdate("UPDATE steward_inbox SET runner = :runner WHERE id = :id AND status = 'RUNNING'")
    int handOver(@Bind("id") long id, @Bind("runner") String runner);

    @SqlQuery("SELECT runner FROM steward_inbox WHERE id = :id AND status = 'RUNNING' AND runner IS NOT NULL")
    Optional<String> runnerOf(@Bind("id") long id);

    /** Returns the running requests with the name of the container that runs them. */
    @SqlQuery("SELECT id, runner FROM steward_inbox WHERE status = 'RUNNING' AND runner IS NOT NULL")
    @RegisterConstructorMapper(Handed.class)
    List<Handed> handed();

    /** A running request and its container. */
    record Handed(long id, String runner) {}

    @SqlQuery("SELECT * FROM service_hold ORDER BY since DESC, service")
    @RegisterRowMapper(ServiceHoldMapper.class)
    List<ServiceHold> holds();

    /** Writes the hold, or refreshes the existing one so the newest press says who holds it. Announced. */
    @SqlQuery("""
            WITH held AS (
                INSERT INTO service_hold (service, actor_kind, actor_id, request_id)
                VALUES (:service, :actorKind, :actorId, :requestId)
                ON CONFLICT (service) DO UPDATE
                    SET since = now(), actor_kind = EXCLUDED.actor_kind, actor_id = EXCLUDED.actor_id,
                        request_id = EXCLUDED.request_id
                RETURNING service
            )
            SELECT count(*) FROM (SELECT pg_notify(:channel, '') FROM held) AS announced
            """)
    int hold(
            @Bind("service") String service,
            @Bind("actorKind") String actorKind,
            @Bind("actorId") @Nullable String actorId,
            @Bind("requestId") @Nullable Long requestId);

    /** Deletes the hold and returns the rows deleted, zero if there was none. Announced if one went. */
    @SqlQuery("""
            WITH released AS (DELETE FROM service_hold WHERE service = :service RETURNING service)
            SELECT count(*) FROM (SELECT pg_notify(:channel, '') FROM released) AS announced
            """)
    int release(@Bind("service") String service);
}
