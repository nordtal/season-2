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
     * @param kind         REPORT, UPDATE or RESTART
     * @param source       DISCORD, GAME or CONSOLE
     * @param requestedBy  a Discord id, a Minecraft name, or {@code null}
     * @param delaySeconds how long from now the updater may act. <b>Zero for everything, since
     *                     2026-09-08</b>: a countdown is started by {@link #startCountdown} once
     *                     the updater knows there is work, and this parameter is kept only so that
     *                     a future caller with a genuine reason to delay a request has a way to
     *                     say so
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
     * <p>{@code not_before <= now()} used to be what made a restart's countdown real - the row sat
     * unclaimable for the length of it. Since 2026-09-08 every request is written due immediately
     * and the countdown is set <em>after</em> the plan is known ({@link #startCountdown}), so this
     * predicate now only bounds a request somebody deliberately schedules. It is kept because a
     * claim that ignored {@code not_before} would silently make such a request impossible.</p>
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

    /**
     * Rewrites a running request's report and leaves its status alone.
     *
     * <p>{@code status = 'RUNNING'} in the WHERE is the whole guard: a progress write that arrives
     * after the request was cancelled, or after another updater settled it, changes nothing. The
     * alternative - writing unconditionally - would let a stage that finished a moment before the
     * cancel overwrite the cancellation with "starting the servers".</p>
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
     * <p>The Discord bot's feed: it remembers the highest id it has drawn and asks for what came
     * after it, so a run asked for <em>in game</em> or from a console appears in the admin channel
     * without anybody having to think of posting it. Before this existed, the only run an admin who
     * had not started it could see was one started in Discord - and those are the ones that already
     * have somebody watching.</p>
     *
     * @param id the last one already seen; {@code 0} for everything
     */
    @SqlQuery("SELECT * FROM update_request WHERE id > :id ORDER BY id")
    java.util.List<UpdateRequest> since(@Bind("id") long id);

    /**
     * The highest id in the table, or zero when it is empty.
     *
     * <p>What the feed starts from, so a bot restarting after a season of updates does not post the
     * whole history into the admin channel. What that costs is the runs that finished while the bot
     * was down, which {@link #finishedWithin(long)} is for.</p>
     */
    @SqlQuery("SELECT coalesce(max(id), 0) FROM update_request")
    long latestId();

    /**
     * Every request that reached a terminal state in the last {@code seconds}.
     *
     * <p>The other half of a boot: a run started in game five minutes ago finished while this bot
     * was restarting, so its id is below {@link #latestId()} and the feed would never see it - which
     * would make the one run nobody watched also the one run nobody ever saw the answer to.</p>
     */
    @SqlQuery("""
            SELECT * FROM update_request
            WHERE finished IS NOT NULL
              AND finished > now() - make_interval(secs => cast(:seconds AS double precision))
            ORDER BY id
            """)
    java.util.List<UpdateRequest> finishedWithin(@Bind("seconds") long seconds);

    /**
     * Starts the countdown on a request this updater has already claimed.
     *
     * <h2>Why the countdown begins here and not when the row was written</h2>
     * Until 2026-09-08 every submitter wrote {@code not_before = now() + 30s} and the updater was
     * simply forbidden to act before it. So a countdown ran for <b>every</b> update asked for,
     * including the overwhelmingly common one where nothing is new: thirty seconds of "the servers
     * are going down" shown to everybody playing, ending in "everything is already current". The
     * order is now resolve first, count down only if the plan has work in it - which means the
     * instant can only be set by the process that has just resolved.
     *
     * <p>{@code status = 'RUNNING'} is the guard, and it is what makes the countdown cancellable:
     * a cancel flips the row to {@code CANCELLED}, so a countdown cannot be started on, or
     * extended over, a request somebody has already withdrawn.</p>
     *
     * @return the row with its new {@code not_before}, or empty when it is no longer running
     */
    @SqlQuery("""
            UPDATE update_request
            SET not_before = now() + make_interval(secs => cast(:seconds AS double precision))
            WHERE id = :id AND status = 'RUNNING'
            RETURNING *
            """)
    Optional<UpdateRequest> startCountdown(@Bind("id") long id, @Bind("seconds") long seconds);

    /**
     * Ends the countdown, atomically, and says whether it was still there to end.
     *
     * <h2>This is the race, and it is decided here rather than by looking first</h2>
     * At the instant the counter reaches zero, one connection is about to stop four servers and
     * another may be carrying out {@code /update cancel}. Both are an {@code UPDATE} on this row,
     * so PostgreSQL serialises them: whichever gets there first holds the row lock, and the second
     * either finds a {@code CANCELLED} row (and matches nothing) or is refused by
     * {@link #cancelCountdown(String)}'s {@code SKIP LOCKED}.
     *
     * <p>An empty answer therefore means one specific thing - somebody cancelled - and the run must
     * stop <b>nothing</b>. Reading the status and then acting on it would leave exactly the window
     * this closes.</p>
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
     * The outage that is counting down right now - what network-control counts down towards, and
     * what a cancel withdraws.
     *
     * <h2>{@code RUNNING} as well as {@code PENDING}, since 2026-09-08</h2>
     * The countdown is set by the updater <em>after</em> it has claimed the row, so a row that is
     * counting down is {@code RUNNING} and not {@code PENDING}. Leaving this at {@code PENDING}
     * would have made the proxy blind to every countdown there is. {@code PENDING} stays in the set
     * because it costs nothing and covers the moment between the row being written and an updater
     * claiming it.
     *
     * <h2>Both kinds that take servers down, since 2026-09-07</h2>
     * It was {@code kind = 'RESTART'} alone, which was complete while a restart was the only thing
     * with a countdown on it. An {@code UPDATE} now stops servers too and carries the same
     * {@code not_before} - so leaving this as it was would have counted down for a restart and
     * said <b>nothing at all</b> before an update, which is the one of the two that also replaces
     * jars. Players would have been dropped mid-sentence with no warning anywhere.
     *
     * <p>{@code not_before > now()} is what keeps a claimed row from looking like a countdown for
     * the whole of a five-minute run: every request is due immediately now, so without it every
     * running update would read as a countdown that has reached zero.</p>
     *
     * @return the outage being counted down, or empty. There is normally at most one; if a second
     *         was asked for, the earlier one is the one that will fire and therefore the one to show
     */
    @SqlQuery("""
            SELECT * FROM update_request
            WHERE status IN ('PENDING', 'RUNNING')
              AND kind IN ('RESTART', 'UPDATE')
              AND not_before > now()
            ORDER BY not_before, id
            LIMIT 1
            """)
    Optional<UpdateRequest> countingDown();

    /**
     * Withdraws the countdown that is running, if there still is one.
     *
     * <p>Guarded by the status rather than by reading first and writing after: the whole point is a
     * race against an updater that may be committing the very same countdown this millisecond, and
     * a check-then-act would lose it. {@code FOR UPDATE SKIP LOCKED} is what turns that race into an
     * answer - {@link #commitCountdown(long)} holds the row lock while it commits, so a cancel
     * arriving in that instant skips the row and answers empty, which is "too late" and is exactly
     * the sentence the admin needs.</p>
     *
     * @param reason what goes into {@code result}, naming who cancelled
     * @return the cancelled row, or empty when there was nothing left to cancel
     */
    @SqlQuery("""
            WITH cancellable AS (
                SELECT id
                FROM update_request
                -- Both kinds and both statuses, for the reasons countingDown() above gives at
                -- length: the button says "Stop the countdown", and a countdown it could not stop
                -- would be worse than no button.
                WHERE status IN ('PENDING', 'RUNNING')
                  AND kind IN ('RESTART', 'UPDATE')
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
     * <p>This is what keeps a countdown honest. The server otherwise sleeps for the poll interval,
     * and a restart whose minute runs out four seconds into a fifteen-second sleep would fire
     * eleven seconds after the counter reached zero in front of the players watching it.</p>
     *
     * @return the earliest {@code not_before} among pending rows, or empty when there are none
     */
    @SqlQuery("SELECT min(not_before) FROM update_request WHERE status = 'PENDING'")
    Optional<java.time.OffsetDateTime> nextDue();

    /**
     * Fails every row still marked {@code RUNNING}.
     *
     * <p>Called once, at updater startup. Nothing is running them: the only process that claims a
     * row is an updater, exactly one {@code serve} may exist (its own advisory lock), and this one
     * has just started. Without it a request killed mid-flight would sit {@code RUNNING} forever
     * and every surface reading it would show a spinner that never stops.</p>
     *
     * <h2>A restart used to be closed as {@code DONE} here, and that inference is gone</h2>
     * It was right when a restart <em>was</em> one Arcane redeploy of the whole project, which took
     * this container down mid-call: finding a {@code RESTART} left {@code RUNNING} on the next boot
     * was how the updater learned the restart it asked for had happened. Since 2026-09-07 a restart
     * cycles the four Minecraft services one at a time and never stops the updater, so a
     * {@code RESTART} row left {@code RUNNING} means the same thing every other kind does - the
     * updater died in the middle of it. Reporting that as success would be the one reading nobody
     * can act on.
     *
     * @param result what to write into those rows
     * @return how many there were
     */
    @SqlUpdate("""
            UPDATE update_request
            SET status = 'FAILED', finished = now(), result = :result
            WHERE status = 'RUNNING'
            """)
    int failOrphans(@Bind("result") String result);
}
