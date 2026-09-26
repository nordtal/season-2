package eu.nordtal.s2.common.update;

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
     * The notification carries no payload and fires only for a committed row. {@code make_interval} adds
     * exact seconds from a bind parameter, unaffected by the session's time zone.
     *
     * @param requestedBy  a Discord id, a Minecraft name, or {@code null}
     * @param delaySeconds how long from now steward-worker may act; zero in practice, as the countdown starts later
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
    UpdateRequest submit(
            @Bind("kind") String kind,
            @Bind("source") String source,
            @Bind("requestedBy") @Nullable String requestedBy,
            @Bind("delaySeconds") long delaySeconds);

    /**
     * The same insert, with the services this run is for.
     *
     * A second statement rather than one with a nullable bind, because the two say different
     * things and the one above is what every existing caller means. {@code scope} is NULL for the
     * whole network; see {@code V27__update_request_scope.sql} for why that is the default rather
     * than an empty string.
     *
     * @param scope comma-separated compose service names, or {@code null} for the whole network
     */
    @SqlQuery("""
            WITH inserted AS (
                INSERT INTO update_request (kind, source, requested_by, not_before, scope)
                VALUES (:kind, :source, :requestedBy,
                        now() + make_interval(secs => cast(:delaySeconds AS double precision)),
                        :scope)
                RETURNING *
            )
            SELECT inserted.*, pg_notify('nordtal_update', '') AS notified
            FROM inserted
            """)
    UpdateRequest submitScoped(
            @Bind("kind") String kind,
            @Bind("source") String source,
            @Bind("requestedBy") @Nullable String requestedBy,
            @Bind("delaySeconds") long delaySeconds,
            @Bind("scope") String scope);

    /** The oldest run that is pending or running, which is what refuses a new one. */
    @SqlQuery("""
            SELECT * FROM update_request
            WHERE status IN ('PENDING', 'RUNNING')
            ORDER BY id
            LIMIT 1
            """)
    Optional<UpdateRequest> open();

    /** @return the {@code scope} column, or {@code null} for a whole-network run and for no row. */
    @SqlQuery("SELECT scope FROM update_request WHERE id = :id")
    @Nullable
    String scope(@Bind("id") long id);

    /**
     * Claims the oldest due request and marks it {@code RUNNING} in the same statement.
     *
     * {@code FOR UPDATE SKIP LOCKED} lets a second worker take the next row instead of the same one.
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

    /**
     * Rewrites a running request's report and leaves its status alone.
     *
     * {@code status = 'RUNNING'} in the WHERE is the whole guard: a progress write that arrives
     * after the request was cancelled, or after another worker settled it, changes nothing. The
     * alternative - writing unconditionally - would let a stage that finished a moment before the
     * cancel overwrite the cancellation with "starting the servers".
     */
    @SqlUpdate("""
            UPDATE update_request
            SET result = :result
            WHERE id = :id AND status = 'RUNNING'
            """)
    int progress(@Bind("id") long id, @Bind("result") String result);

    @SqlQuery("SELECT * FROM update_request WHERE id = :id")
    Optional<UpdateRequest> find(@Bind("id") long id);

    /**
     * Every request written after the one named, oldest first.
     *
     * The Discord bot's feed: it remembers the highest id it has drawn and asks for what came
     * after it, so a run asked for <em>in game</em> or from a console appears in the admin channel
     * without anybody having to think of posting it. Before this existed, the only run an admin who
     * had not started it could see was one started in Discord - and those are the ones that already
     * have somebody watching.
     *
     * @param id the last one already seen; {@code 0} for everything
     */
    @SqlQuery("SELECT * FROM update_request WHERE id > :id ORDER BY id")
    java.util.List<UpdateRequest> since(@Bind("id") long id);

    /**
     * The most recent requests, newest first.
     *
     * For a person looking at a list rather than for a process deciding something, which is why
     * it is bounded by a count and not by a time: "what happened here lately" has an answer of a
     * screenful, and a season's worth of rows is not it.
     */
    @SqlQuery("SELECT * FROM update_request ORDER BY id DESC LIMIT :limit")
    java.util.List<UpdateRequest> recent(@Bind("limit") int limit);

    /**
     * The highest id in the table, or zero when it is empty.
     *
     * What the feed starts from, so a bot restarting after a season of updates does not post the
     * whole history into the admin channel. What that costs is the runs that finished while the bot
     * was down, which {@link #finishedWithin(long)} is for.
     */
    @SqlQuery("SELECT coalesce(max(id), 0) FROM update_request")
    long latestId();

    /**
     * Every request that reached a terminal state in the last {@code seconds}.
     *
     * The other half of a boot: a run started in game five minutes ago finished while this bot
     * was restarting, so its id is below {@link #latestId()} and the feed would never see it - which
     * would make the one run nobody watched also the one run nobody ever saw the answer to.
     */
    @SqlQuery("""
            SELECT * FROM update_request
            WHERE finished IS NOT NULL
              AND finished > now() - make_interval(secs => cast(:seconds AS double precision))
            ORDER BY id
            """)
    java.util.List<UpdateRequest> finishedWithin(@Bind("seconds") long seconds);

    /**
     * Every {@code BACKUP} that reached {@code DONE} inside the window, newest first.
     *
     * <b>Why the list and not a {@code LIMIT 1}</b>
     *
     * {@code status = 'DONE'} is only half the question - the other half is whether the report in
     * {@code result} shows anything saved, and that is JSON this module parses in Java rather than
     * in SQL. A single row would therefore have to be believed: a run that settled {@code DONE}
     * having saved nothing would come back as "there is a backup", which is A23 exactly. So the
     * caller walks them newest first and stops at the first one it can prove.
     *
     * <b>Why the window is a parameter and not a constant</b>
     *
     * It bounds both the scan and the parsing, and every caller has one anyway - "there was a good
     * backup in July" is never the answer anybody wants. Without it this is an unbounded scan of a
     * table that grows for a whole season.
     *
     * No index is declared for this. The table takes a handful of rows a day and the window is
     * hours, so the planner reads a few dozen of them; an index on {@code (kind, status, finished)}
     * would be maintained on every write for a query that runs once a night.
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
     * Starts the countdown on a request this steward-worker has already claimed.
     *
     * <b>Why the countdown begins here and not when the row was written</b>
     *
     * A countdown is only worth running when the plan has work in it, so resolving happens first
     * and counting down second - which means the instant can only be set by the process that has
     * just resolved.
     *
     * {@code status = 'RUNNING'} is the guard, and it is what makes the countdown cancellable:
     * a cancel flips the row to {@code CANCELLED}, so a countdown cannot be started on, or
     * extended over, a request somebody has already withdrawn.
     *
     * The {@code pg_notify} rides this statement rather than only {@link #submit}: a listener
     * that heard only the earlier, premature notification from {@code submit} would have no way to
     * learn that the real countdown - the one {@link #countingDown()} counts towards - had started,
     * except the poll behind it.
     *
     * @return the row with its new {@code not_before}, or empty when it is no longer running
     */
    @SqlQuery("""
            WITH updated AS (
                UPDATE update_request
                SET not_before = now() + make_interval(secs => cast(:seconds AS double precision))
                WHERE id = :id AND status = 'RUNNING'
                RETURNING *
            )
            SELECT updated.*, pg_notify('nordtal_update', '') AS notified
            FROM updated
            """)
    Optional<UpdateRequest> startCountdown(@Bind("id") long id, @Bind("seconds") long seconds);

    /**
     * Ends the countdown, atomically, and says whether it was still there to end.
     *
     * <b>This is the race, and it is decided here rather than by looking first</b>
     *
     * At the instant the counter reaches zero, one connection is about to stop four servers and
     * another may be carrying out {@code /update cancel}. Both are an {@code UPDATE} on this row,
     * so PostgreSQL serialises them: whichever gets there first holds the row lock, and the second
     * either finds a {@code CANCELLED} row (and matches nothing) or is refused by
     * {@link #cancelCountdown(String)}'s {@code SKIP LOCKED}.
     *
     * An empty answer therefore means one specific thing - somebody cancelled - and the run must
     * stop <b>nothing</b>. Reading the status and then acting on it would leave exactly the window
     * this closes.
     *
     * @return the id when the run may go ahead, empty when it was cancelled
     */
    @SqlQuery("""
            UPDATE update_request
            SET not_before = now()
            WHERE id = :id AND status = 'RUNNING'
            RETURNING id
            """)
    Optional<Long> commitCountdown(@Bind("id") long id);

    /**
     * Returns the outage counting down right now, which proxy shows and a cancel withdraws.
     *
     * The countdown is set after the claim, so {@code RUNNING} rows count. The kind list is a literal that
     * {@code UpdateDirectoryIntegrationTest#everythingThatStopsServersCountsDown} holds against
     * {@link UpdateKind#stopsServers()}. {@code not_before > now()} keeps a running request from looking like one.
     *
     * @return the outage being counted down, the earliest if there are several, or empty
     */
    @SqlQuery("""
            SELECT * FROM update_request
            WHERE status IN ('PENDING', 'RUNNING')
              AND kind IN ('RESTART', 'UPDATE', 'BACKUP', 'DOWN')
              AND not_before > now()
            ORDER BY not_before, id
            LIMIT 1
            """)
    Optional<UpdateRequest> countingDown();

    /**
     * The run that is happening right now - claimed, past its countdown, servers going down.
     *
     * The complement of {@link #countingDown()}, which is the same rows <em>before</em>
     * {@code not_before}. Together they are the whole of "an outage is under way", and the proxy
     * needs both: the countdown is when it moves players out of the way, and this is the minutes
     * afterwards during which the waiting room has to keep saying why they are sitting there.
     *
     * {@code RUNNING} alone, without a kind filter: a {@code BACKUP} stops the same servers for
     * the same minutes, and a player held through one deserves the same sentence as a player held
     * through an update. A {@code REPORT} never reaches {@code RUNNING} for long enough to matter
     * and moves nothing, so including it costs nothing either.
     *
     * @return the running request, or empty
     */
    @SqlQuery("""
            SELECT * FROM update_request
            WHERE status = 'RUNNING'
              AND not_before <= now()
            ORDER BY id
            LIMIT 1
            """)
    Optional<UpdateRequest> running();

    /**
     * Withdraws the countdown that is running, if there still is one.
     *
     * Guarded by the status rather than by reading first and writing after: the whole point is a
     * race against a worker that may be committing the very same countdown this millisecond, and
     * a check-then-act would lose it. {@code FOR UPDATE SKIP LOCKED} is what turns that race into an
     * answer - {@link #commitCountdown(long)} holds the row lock while it commits, so a cancel
     * arriving in that instant skips the row and answers empty, which is "too late" and is exactly
     * the sentence the admin needs.
     *
     * @param reason what goes into {@code result}, naming who cancelled
     * @return the cancelled row, or empty when there was nothing left to cancel
     */
    @SqlQuery("""
            WITH cancellable AS (
                SELECT id
                FROM update_request
                -- The same four kinds and both statuses, for the reasons countingDown()
                -- above gives at length: the button says "Stop the countdown", and a countdown it
                -- could not stop would be worse than no button. BACKUP was missing here until
                -- 2026-09-15 for the same reason it was missing there.
                WHERE status IN ('PENDING', 'RUNNING')
                  AND kind IN ('RESTART', 'UPDATE', 'BACKUP', 'DOWN')
                  AND not_before > now()
                ORDER BY not_before, id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            )
            UPDATE update_request
            SET status = 'CANCELLED', finished = now(), result = :reason
            WHERE id IN (SELECT id FROM cancellable)
            RETURNING *
            """)
    Optional<UpdateRequest> cancelCountdown(@Bind("reason") String reason);

    /**
     * When the next pending request becomes due, whether or not that is now.
     *
     * This is what keeps a countdown honest. The server otherwise sleeps for the poll interval,
     * and a restart whose minute runs out four seconds into a fifteen-second sleep would fire
     * eleven seconds after the counter reached zero in front of the players watching it.
     *
     * @return the earliest {@code not_before} among pending rows, or empty when there are none
     */
    @SqlQuery("SELECT min(not_before) FROM update_request WHERE status = 'PENDING'")
    Optional<java.time.OffsetDateTime> nextDue();

    /**
     * Fails every row still marked {@code RUNNING}; called once at worker startup.
     *
     * Only one worker serves, so a row still running was interrupted. A restart is no exception.
     *
     * @return how many there were
     */
    @SqlUpdate("""
            UPDATE update_request
            SET status = 'FAILED', finished = now(), result = :result
            WHERE status = 'RUNNING'
            """)
    int failOrphans(@Bind("result") String result);

    /** Every service being held down, newest first. Usually none. */
    @SqlQuery("SELECT * FROM service_hold ORDER BY since DESC, service")
    @RegisterRowMapper(ServiceHoldMapper.class)
    List<ServiceHold> holds();

    /**
     * Writes the hold, or refreshes the one already there.
     *
     * {@code ON CONFLICT DO UPDATE} rather than an insert that can fail: pressing Down on a
     * service that is already down is not an error, it is somebody making sure. The newer press
     * wins, so {@code since} and {@code held_by} say who is actually holding it.
     */
    @SqlUpdate("""
            INSERT INTO service_hold (service, held_by, request_id)
            VALUES (:service, :heldBy, :requestId)
            ON CONFLICT (service) DO UPDATE
                SET since = now(), held_by = EXCLUDED.held_by, request_id = EXCLUDED.request_id
            """)
    void hold(
            @Bind("service") String service,
            @Bind("heldBy") @Nullable String heldBy,
            @Bind("requestId") @Nullable Long requestId);

    /** @return how many rows went away; zero when it was not being held, which is not an error */
    @SqlUpdate("DELETE FROM service_hold WHERE service = :service")
    int release(@Bind("service") String service);
}
