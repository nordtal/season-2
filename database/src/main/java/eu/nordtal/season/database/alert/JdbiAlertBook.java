package eu.nordtal.season.database.alert;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.JournalDetail.ChannelFailure;
import eu.nordtal.season.database.JournalDetail.FailedStep;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.Journal;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.payment.PaymentRequestStatus;
import eu.nordtal.season.database.payment.TabRefusal;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.messages.value.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.Nullable;

/** Implements {@link AlertBook} with JDBI over {@code journal_entry} and its alert tables. */
final class JdbiAlertBook implements AlertBook {

    /** Selects every alert with the values of its detail. Measures, steps and channels are read per entry. */
    private static final String ALERTS = """
            SELECT e.id, e.occurred, e.action, e.actor_kind, e.actor_id, e.subject, e.level, e.run, e.payment,
                   r.kind AS run_kind, bp.bank_payment, bp.problem, bp.cents,
                   coalesce(bp.reference, p.reference) AS reference, p.status, p.tab_refusal,
                   jr.role, jr.given, jr.name, je.error
            FROM journal_entry e
                     LEFT JOIN steward_inbox r ON r.id = e.run
                     LEFT JOIN payment_request p ON p.id = e.payment
                     LEFT JOIN journal_bank_payment bp ON bp.entry = e.id
                     LEFT JOIN journal_role jr ON jr.entry = e.id
                     LEFT JOIN journal_error je ON je.entry = e.id
            WHERE e.level IS NOT NULL
            """;

    private final Jdbi jdbi;

    JdbiAlertBook(final DataSource dataSource) {
        this.jdbi = Jdbis.over(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public UUID raise(final Alert alert, final Actor actor) {
        return jdbi.inTransaction(handle -> insert(handle, alert, actor));
    }

    @Override
    public boolean raiseOnce(final Alert alert, final Actor actor) {
        final JournalDetail detail = alert.detail();
        final Long run = detail == null ? null : detail.run();
        final Long bankPayment = detail instanceof JournalDetail.PaymentFlagged flagged ? flagged.bankPayment() : null;
        if (run == null && bankPayment == null) {
            throw new IllegalArgumentException(
                    alert.action() + " has neither a run nor a bank payment to raise once by");
        }
        // The unique indexes back this check. A conflict clause would need more than INSERT from the bot's role.
        return jdbi.inTransaction(handle -> {
            final boolean raised = handle.createQuery("""
                            SELECT EXISTS (SELECT 1 FROM journal_entry e
                                                    LEFT JOIN journal_bank_payment b ON b.entry = e.id
                                           WHERE e.level IS NOT NULL AND e.action = :action
                                             AND (e.run = :run OR b.bank_payment = :bankPayment))""")
                    .bind("action", alert.action().name())
                    .bind("run", run)
                    .bind("bankPayment", bankPayment)
                    .mapTo(Boolean.class)
                    .one();
            if (!raised) {
                insert(handle, alert, actor);
            }
            return !raised;
        });
    }

    /** Writes the entry and its measures and sends the signal in the same transaction. */
    private static UUID insert(final Handle handle, final Alert alert, final Actor actor) {
        Objects.requireNonNull(alert, "alert");
        final JournalDetail detail = alert.detail();
        final DiscordId member = detail == null ? null : detail.member();
        final UUID entry = Journal.write(
                handle,
                new AuditLine(alert.action(), actor, member, null, detail),
                alert.level().name());
        final List<Measure> measured = alert.measured();
        for (int position = 0; position < measured.size(); position++) {
            final Measure measure = measured.get(position);
            handle.createUpdate("""
                            INSERT INTO alert_measure (entry, position, gauge, subject, value, "limit", state)
                            VALUES (:entry, :position, :gauge, :subject, :value, :limit, :state)""")
                    .bind("entry", entry)
                    .bind("position", position)
                    .bind("gauge", measure.gauge().name())
                    .bind("subject", measure.subject())
                    .bind("value", measure.value())
                    .bind("limit", measure.limit())
                    .bind("state", measure.state())
                    .execute();
        }
        handle.createQuery("SELECT pg_notify('nordtal_alert', '') IS NULL")
                .mapTo(Boolean.class)
                .one();
        return entry;
    }

    @Override
    public List<RaisedAlert> claimUnrouted() {
        return jdbi.inTransaction(handle -> {
            final List<UUID> claimed = handle.createQuery("""
                            UPDATE journal_entry SET routed = now()
                            WHERE level IS NOT NULL AND routed IS NULL
                            RETURNING id""").mapTo(UUID.class).list();
            return claimed.isEmpty()
                    ? List.<RaisedAlert>of()
                    : read(
                            handle,
                            "AND e.id = ANY(:ids) ORDER BY e.occurred, e.id",
                            "ids",
                            claimed.toArray(UUID[]::new));
        });
    }

    @Override
    public List<RaisedAlert> recent(final int limit) {
        return jdbi.withHandle(handle ->
                read(handle, "ORDER BY e.occurred DESC, e.id DESC LIMIT :limit", "limit", Math.max(0, limit)));
    }

    @Override
    public Optional<RaisedAlert> alert(final UUID id) {
        return jdbi
                .withHandle(handle -> read(handle, "AND e.id = :id", "id", Objects.requireNonNull(id, "id")))
                .stream()
                .findFirst();
    }

    private static List<RaisedAlert> read(
            final Handle handle, final String rest, final String name, final Object value) {
        return handle.createQuery(ALERTS + rest)
                .bind(name, value)
                .map((rows, context) -> raisedOf(handle, rows))
                .list();
    }

    private static RaisedAlert raisedOf(final Handle handle, final ResultSet rows) throws SQLException {
        final UUID id = Objects.requireNonNull(rows.getObject("id", UUID.class), "id");
        final JournalAction action = JournalAction.valueOf(rows.getString("action"));
        final List<Measure> measured = handle.createQuery("""
                        SELECT gauge, subject, value, "limit", state FROM alert_measure
                        WHERE entry = :entry ORDER BY position""")
                .bind("entry", id)
                .map((measures, context) -> new Measure(
                        Measure.Gauge.valueOf(measures.getString("gauge")),
                        measures.getString("subject"),
                        measures.getObject("value", Double.class),
                        measures.getObject("limit", Double.class),
                        measures.getString("state")))
                .list();
        return new RaisedAlert(
                id,
                Objects.requireNonNull(rows.getTimestamp("occurred"), "occurred")
                        .toInstant(),
                Actor.of(
                        Objects.requireNonNull(rows.getString("actor_kind"), "actor_kind"), rows.getString("actor_id")),
                new Alert(
                        action,
                        Alert.Level.valueOf(rows.getString("level")),
                        measured,
                        detail(handle, action, id, rows)));
    }

    private static @Nullable JournalDetail detail(
            final Handle handle, final JournalAction action, final UUID id, final ResultSet rows) throws SQLException {
        final DiscordId member = DiscordId.ofNullable(rows.getString("subject"));
        final String error = rows.getString("error");
        final String name = rows.getString("name");
        return switch (action) {
            case RUN_FAILED -> runFailed(handle, rows);
            case PAYMENT_NEEDS_LOOK -> paymentFlagged(rows);
            case LINK_REFUSED ->
                new JournalDetail.LinkRefused(
                        Objects.requireNonNull(rows.getObject("payment", UUID.class), "payment"),
                        Objects.requireNonNull(rows.getString("reference"), "reference"),
                        tabRefusal(rows.getString("tab_refusal")));
            case LINK_FAILED, PURCHASE_FAILED, DM_NOT_DELIVERED ->
                new JournalDetail.MemberFailed(Objects.requireNonNull(member, "subject"), error);
            case ROLE_NOT_CHANGED ->
                new JournalDetail.RoleNotChanged(
                        DiscordRole.valueOf(rows.getString("role")),
                        rows.getBoolean("given"),
                        Objects.requireNonNull(member, "subject"),
                        Objects.requireNonNull(error, "error"));
            case ROLE_AMBIGUOUS, ROLE_NOT_CREATED ->
                new JournalDetail.RoleNamed(Objects.requireNonNull(name, "name"), error);
            case LOCK_NOT_KEPT -> new JournalDetail.ChannelsNotClosed(channels(handle, id));
            default -> null;
        };
    }

    private static JournalDetail runFailed(final Handle handle, final ResultSet rows) throws SQLException {
        final long run = rows.getLong("run");
        final List<FailedStep> steps = handle.createQuery("""
                        SELECT step, service, outcome, reason FROM run_step
                        WHERE run = :run AND outcome IN ('FAILED', 'WARNING') ORDER BY seq""")
                .bind("run", run)
                .map((step, context) -> new FailedStep(
                        RunStep.Kind.valueOf(step.getString("step")),
                        step.getString("service"),
                        RunStep.Outcome.valueOf(step.getString("outcome")),
                        step.getString("reason") == null ? null : RunStep.Reason.valueOf(step.getString("reason"))))
                .list();
        return new JournalDetail.RunFailed(run, UpdateKind.valueOf(rows.getString("run_kind")), steps);
    }

    private static JournalDetail paymentFlagged(final ResultSet rows) throws SQLException {
        final String status = rows.getString("status");
        return new JournalDetail.PaymentFlagged(
                JournalDetail.PaymentFlagged.Problem.valueOf(rows.getString("problem")),
                rows.getLong("bank_payment"),
                Money.euroCents(rows.getInt("cents")),
                Objects.requireNonNull(rows.getString("reference"), "reference"),
                rows.getObject("payment", UUID.class),
                status == null ? null : PaymentRequestStatus.valueOf(status));
    }

    private static TabRefusal tabRefusal(final @Nullable String refusal) {
        return refusal == null ? TabRefusal.UNKNOWN : TabRefusal.valueOf(refusal);
    }

    private static List<ChannelFailure> channels(final Handle handle, final UUID entry) {
        return handle.createQuery(
                        "SELECT channel, error FROM journal_channel_failure WHERE entry = :entry ORDER BY position")
                .bind("entry", entry)
                .map((row, context) -> new ChannelFailure(row.getString("channel"), row.getString("error")))
                .list();
    }
}
