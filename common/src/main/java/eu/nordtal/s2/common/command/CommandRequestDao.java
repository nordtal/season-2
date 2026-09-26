package eu.nordtal.s2.common.command;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jspecify.annotations.Nullable;

/**
 * The SQL behind {@link CommandRequests}. Package-private: the interface is the API.
 *
 * <b>Four statements, and three of them are guarded by their own {@code WHERE}</b>
 *
 * Every transition here is conditional on the status it is coming from, so two processes racing on
 * one row end with one winner and no lost update. That is not theoretical: an admin can type the
 * same command in Discord and in chat, and the giving-up asker and a slow target reach for the same
 * row from opposite ends.
 */
@RegisterConstructorMapper(CommandRequest.class)
interface CommandRequestDao {

    /**
     * Write a request and wake whoever is listening, in one statement.
     *
     * <b>Why the notification is a cross join and not a bare CTE</b>
     *
     * {@code notified} is a plain {@code SELECT}, not a data-modifying one, so PostgreSQL is under
     * no obligation to execute it if nothing reads its output - unlike the {@code INSERT} CTEs in
     * {@code PhaseDao}, whose comment says exactly that and is easy to over-generalise from. Joining
     * it into the outer query is what makes it run. Both are one row, so the join is one row.
     *
     * It rides inside the statement for the same reason every other {@code pg_notify} in this
     * repository does: a notification is only ever emitted for a row that committed.
     */
    @SqlQuery("""
            WITH inserted AS (
                INSERT INTO command_request
                    (target, command, arguments, source, requested_by,
                     discord_id, mc_uuid, locale, expires)
                VALUES (:target, :command, :arguments, :source, :requestedBy,
                        :discordId, :minecraftId, :locale, :expires)
                RETURNING id, target
            ),
                 notified AS (
                     SELECT pg_notify('nordtal_command', inserted.target) AS sent
                     FROM inserted
                 )
            SELECT inserted.id
            FROM inserted,
                 notified
            """)
    long submit(
            @Bind("target") String target,
            @Bind("command") String command,
            @Bind("arguments") String arguments,
            @Bind("source") String source,
            @Bind("requestedBy") String requestedBy,
            @Bind("discordId") @Nullable String discordId,
            @Bind("minecraftId") @Nullable UUID minecraftId,
            @Bind("locale") String locale,
            @Bind("expires") Instant expires);

    /**
     * The same insert, carrying its journal line in the same statement.
     *
     * <b>Why this one is transactional when the journal generally is not</b>
     *
     * {@code AuditDirectory#record} says in as many words that a journal line is deliberately
     * <em>not</em> part of the transaction of the action it describes: a grant that lands without
     * its line is a bug worth finding, and a member locked out because the bookkeeping failed is
     * worse. That argument does not survive being pointed at this table. A {@code command_request}
     * row is not a fact about the past, it is <b>work waiting to be claimed</b>: if the insert
     * commits and the journal line then fails, the interface answers the operator with an error
     * while a target quietly picks the row up and runs the command - and the operator, told it did
     * not work, presses the button again. Two unlocked milestones, one of them in nobody's name.
     *
     * So the two rows are one statement. Not a JDBI transaction around two DAOs - one statement,
     * which cannot half-happen and needs nobody to remember to open it. {@code journalled} is a
     * data-modifying CTE, and PostgreSQL runs one of those exactly once whether or not the outer
     * query reads its output; {@code notified} above is a plain {@code SELECT} and is joined in for
     * precisely the opposite reason. The phase switch writes its own row the same way.
     */
    @SqlQuery("""
            WITH inserted AS (
                INSERT INTO command_request
                    (target, command, arguments, source, requested_by,
                     discord_id, mc_uuid, locale, expires)
                VALUES (:target, :command, :arguments, :source, :requestedBy,
                        :discordId, :minecraftId, :locale, :expires)
                RETURNING id, target
            ),
                 journalled AS (
                     INSERT INTO audit_log (action, actor, subject, mc_uuid, detail)
                     VALUES (:action, :actor, :subject, :auditUuid, :detail)
                 ),
                 notified AS (
                     SELECT pg_notify('nordtal_command', inserted.target) AS sent
                     FROM inserted
                 )
            SELECT inserted.id
            FROM inserted,
                 notified
            """)
    long submitJournalled(
            @Bind("target") String target,
            @Bind("command") String command,
            @Bind("arguments") String arguments,
            @Bind("source") String source,
            @Bind("requestedBy") String requestedBy,
            @Bind("discordId") @Nullable String discordId,
            @Bind("minecraftId") @Nullable UUID minecraftId,
            @Bind("locale") String locale,
            @Bind("expires") Instant expires,
            @Bind("action") String action,
            @Bind("actor") String actor,
            @Bind("subject") String subject,
            @Bind("auditUuid") UUID auditUuid,
            @Bind("detail") String detail);

    /**
     * Take the oldest pending request for this target, atomically.
     *
     * <b>{@code FOR UPDATE SKIP LOCKED}</b>
     *
     * There is meant to be one process per target, and the lock is here because "meant to be" is
     * not a guarantee: a rolling restart briefly runs two, and a claim that were two statements
     * would hand one command to both of them. {@code SKIP LOCKED} makes the loser take the next row
     * rather than block behind the winner.
     *
     * <b>{@code expires > now()} is the target's half of the boundary</b>
     *
     * The asking side writes {@code EXPIRED} when it stops waiting. Between the two there is a
     * window where a row is still {@code PENDING} and nobody is listening for its answer any more,
     * and running a command into that window is how somebody's aura gets corrected twice. Refusing
     * to claim it closes the window from this end; the asker's own update closes it from the other.
     */
    @SqlQuery("""
            UPDATE command_request
            SET status  = 'RUNNING',
                started = now()
            WHERE id = (SELECT id
                        FROM command_request
                        WHERE target = :target
                          AND status = 'PENDING'
                          AND expires > now()
                        ORDER BY id
                            FOR UPDATE SKIP LOCKED
                        LIMIT 1)
            RETURNING id, command, arguments, source, requested_by, discord_id,
                mc_uuid AS minecraft_id, locale, expires
            """)
    Optional<CommandRequest> claim(@Bind("target") String target);

    /**
     * Settle a claimed request.
     *
     * {@code AND status = 'RUNNING'} so that a target which somehow settles a row twice writes
     * once. The second call updates nothing and says so through its row count, which is what
     * {@link CommandRequests#finish} logs rather than swallows.
     */
    @SqlUpdate("""
            UPDATE command_request
            SET status   = :status,
                finished = now(),
                result   = :result
            WHERE id = :id
              AND status = 'RUNNING'
            """)
    int finish(@Bind("id") long id, @Bind("status") String status, @Bind("result") String result);

    /**
     * Give up on a request nothing has claimed.
     *
     * {@code AND status = 'PENDING'} is the whole of the race: a target that claimed the row a
     * millisecond ago keeps it, and the asker's timeout does nothing rather than cancelling work
     * that is already running. The asker finds out by reading the outcome afterwards.
     */
    @SqlUpdate("""
            UPDATE command_request
            SET status   = 'EXPIRED',
                finished = now()
            WHERE id = :id
              AND status = 'PENDING'
            """)
    int expire(@Bind("id") long id);

    /**
     * The status and, once it is settled, the answer - in one round trip.
     *
     * Two queries would be two, and this one is polled: a Discord interaction waiting for a
     * remote command asks roughly twice a second for as long as it waits. {@code result} is null
     * until the row settles, which is why this maps to a row record with a nullable field rather
     * than to {@link CommandOutcome} directly - an {@code Optional} column mapper would have to
     * decide what an absent row means, and an absent row here means a different thing (a request id
     * nobody wrote) than a null column.
     */
    @SqlQuery("SELECT status, result FROM command_request WHERE id = :id")
    @RegisterConstructorMapper(OutcomeRow.class)
    Optional<OutcomeRow> outcome(@Bind("id") long id);

    /**
     * Deletes every settled request older than the retention window.
     *
     * <b>Why the table needs one at all</b>
     *
     * A settled row keeps the asker's name, their Discord id, their Minecraft account, the arguments
     * they typed and the answer they were given - and V11 calls a request "a message in flight",
     * which is a lifetime the table had no way of honouring: there was no deletion path anywhere.
     * The volume is not the argument (a season is a few dozen admin commands), the identifiers are.
     *
     * <b>Settled only.</b> A {@code PENDING} or {@code RUNNING} row is never touched however old
     * it looks: the one thing worse than keeping a row too long is deleting one somebody is still
     * waiting on. Those are bounded from both ends already - the asker expires its own, and a target
     * refuses to claim one whose {@code expires} has passed.
     *
     * {@code make_interval(days => :days)} and not {@code interval 'N days'}, which is the rule
     * this repository has carried since the {@code make_interval} bug in stage A: a day is never a
     * string that has a number pasted into it.
     */
    @SqlUpdate("""
            DELETE FROM command_request
            WHERE status IN ('DONE', 'FAILED', 'EXPIRED')
              AND finished < now() - make_interval(days => :days)
            """)
    int deleteSettledOlderThan(@Bind("days") int days);

    /** One row of {@link #outcome}: the status, and the answer that may not be there yet. */
    record OutcomeRow(String status, @Nullable String result) {}
}
