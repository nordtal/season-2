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
 * The SQL behind {@link CommandRequests}.
 * Every transition is guarded by the status it comes from, so two processes racing on one row end with one winner.
 */
@RegisterConstructorMapper(CommandRequest.class)
interface CommandRequestDao {

    /**
     * Writes a request and wakes whoever is listening, in one statement.
     * The notification is cross-joined in, because PostgreSQL may skip a plain {@code SELECT} CTE nobody reads.
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
     * Writes the request, its journal line and the notification in one statement.
     * A committed row is work a target will run, so its line must not fail separately.
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
            @Bind("actor") @Nullable String actor,
            @Bind("subject") @Nullable String subject,
            @Bind("auditUuid") @Nullable UUID auditUuid,
            @Bind("detail") @Nullable String detail);

    /**
     * Claims the oldest pending, unexpired request for this target, atomically.
     * {@code SKIP LOCKED} makes a second process take the next row instead of the same one.
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
     * Settles a claimed request.
     * The {@code RUNNING} guard makes a second call update nothing, which its row count reports.
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

    /** Gives up on a request nothing has claimed; a target that already claimed it keeps it. */
    @SqlUpdate("""
            UPDATE command_request
            SET status   = 'EXPIRED',
                finished = now()
            WHERE id = :id
              AND status = 'PENDING'
            """)
    int expire(@Bind("id") long id);

    /** Returns the status and, once settled, the answer in one round trip, as it is polled. */
    @SqlQuery("SELECT status, result FROM command_request WHERE id = :id")
    @RegisterConstructorMapper(OutcomeRow.class)
    Optional<OutcomeRow> outcome(@Bind("id") long id);

    /**
     * Deletes every settled request older than the retention window.
     * A pending or running row is never touched, however old.
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
