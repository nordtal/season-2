package eu.nordtal.season.database.inbox;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.DatabaseJson;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.Journal;
import eu.nordtal.season.database.notify.SignalHub;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.StatementContext;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One consumer's inbox: submit, claim, progress, settle, expire and cancel, each announced on the table's channel.
 * A notification is never the state: a consumer that wakes up claims until nothing is due. It borrows its pool.
 */
public final class Inbox<P> {

    private static final Logger log = LoggerFactory.getLogger(Inbox.class);

    /** How a request is written and announced in one statement, so a notification only exists for a committed row. */
    private static final String SUBMIT = """
            WITH inserted AS (
                INSERT INTO <table> (kind, payload, actor_kind, actor_id, scheduled_for, expires)
                VALUES (:kind, cast(:payload AS jsonb), :actorKind, :actorId,
                        now() + make_interval(secs => cast(:delay AS double precision)),
                        now() + make_interval(secs => cast(:delay AS double precision) + cast(:patience AS double precision)))
                RETURNING *
            )
            SELECT inserted.*, pg_notify(:channel, '') AS notified FROM inserted
            """;

    /** Takes the oldest due row; {@code SKIP LOCKED} keeps two consumers from ever claiming one row. */
    private static final String CLAIM = """
            WITH claimable AS (
                SELECT id FROM <table>
                WHERE status = 'PENDING'
                  AND scheduled_for <= now()
                  AND (expires IS NULL OR expires > now())
                ORDER BY scheduled_for, id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            ),
            claimed AS (
                UPDATE <table> SET status = 'RUNNING', started = now()
                WHERE id IN (SELECT id FROM claimable)
                RETURNING *
            )
            SELECT claimed.*, pg_notify(:channel, '') AS notified FROM claimed
            """;

    /** Moves one row from one status to another, and only from that one, so a second call changes nothing. */
    private static final String MOVE = """
            WITH moved AS (
                UPDATE <table>
                SET status = :to,
                    finished = CASE WHEN :settles THEN now() END,
                    started = CASE WHEN :to = 'PENDING' THEN NULL ELSE started END,
                    outcome = coalesce(cast(:outcome AS jsonb), outcome)
                WHERE id = :id AND status = :from
                RETURNING *
            )
            SELECT moved.*, pg_notify(:channel, '') AS notified FROM moved
            """;

    /** Every column, with a pending row past its expiry read as expired, so an asker needs no write to see it. */
    private static final String READ = """
            SELECT id, kind, payload,
                   CASE WHEN status = 'PENDING' AND expires <= now() THEN 'EXPIRED' ELSE status END AS status,
                   actor_kind, actor_id, requested, scheduled_for, expires, started, finished, outcome
            FROM <table>
            """;

    private final Jdbi jdbi;
    private final InboxTable<P> table;
    private final AtomicBoolean draining = new AtomicBoolean();

    private Inbox(final DataSource dataSource, final InboxTable<P> table) {
        this.jdbi = Jdbis.over(Objects.requireNonNull(dataSource, "dataSource"));
        this.table = Objects.requireNonNull(table, "table");
    }

    /** Returns the inbox over {@code table}, borrowing a pool somebody else owns and closes. */
    public static <P> Inbox<P> over(final DataSource dataSource, final InboxTable<P> table) {
        return new Inbox<>(dataSource, table);
    }

    /**
     * Returns the inbox of its one consumer at that consumer's start, failing what its last start left running.
     *
     * @param consumer who answers the table, as a sentence names it: "the bot", "smp"
     */
    public static <P> Inbox<P> takeOver(final DataSource dataSource, final InboxTable<P> table, final String consumer) {
        Objects.requireNonNull(consumer, "consumer");
        final Inbox<P> inbox = over(dataSource, table);
        final int orphans = inbox.settleOrphans(Map.of("error", consumer + " restarted while it ran this"));
        if (orphans > 0) {
            log.warn("{} request(s) in {} were left running by the last start of {}", orphans, table, consumer);
        }
        return inbox;
    }

    /** Returns the table this inbox works on. */
    public InboxTable<P> table() {
        return table;
    }

    /** Writes a request that is due at once and waits however long it takes, and wakes the consumer. */
    public Request<P> submit(final P payload, final Actor actor) {
        return submit(payload, actor, Schedule.NOW);
    }

    /** Writes a request for its time and wakes the consumer. */
    public Request<P> submit(final P payload, final Actor actor, final Schedule schedule) {
        return jdbi.withHandle(handle -> insert(handle, payload, actor, schedule));
    }

    /** Writes a request and its journal line in one transaction, so neither exists without the other. */
    public Request<P> submit(final P payload, final Actor actor, final Schedule schedule, final AuditLine journal) {
        Objects.requireNonNull(journal, "journal");
        return jdbi.inTransaction(handle -> {
            Journal.write(handle, journal);
            return insert(handle, payload, actor, schedule);
        });
    }

    /**
     * Writes a request through a transaction the caller holds, so it commits with the caller's own statements.
     * The consumer is woken when that transaction commits.
     */
    public Request<P> submitWithin(final Handle handle, final P payload, final Actor actor) {
        return insert(Objects.requireNonNull(handle, "handle"), payload, actor, Schedule.NOW);
    }

    private Request<P> insert(final Handle handle, final P payload, final Actor actor, final Schedule schedule) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(schedule, "schedule");
        final Duration patience = schedule.patience();
        return handle.createQuery(SUBMIT)
                .define("table", table.name())
                .bind("kind", table.kindOf(payload))
                .bind("payload", DatabaseJson.encode(payload))
                .bind("actorKind", actor.kind().name())
                .bind("actorId", actor.id())
                .bind("delay", seconds(schedule.delay()))
                .bind("patience", patience == null ? null : seconds(patience))
                .bind("channel", table.channel().sqlName())
                .map(this::map)
                .one();
    }

    /** Claims the oldest due request and marks it running; call it until empty, since one signal may stand for many. */
    public Optional<Request<P>> claim() {
        return jdbi.withHandle(handle -> handle.createQuery(CLAIM)
                .define("table", table.name())
                .bind("channel", table.channel().sqlName())
                .map(this::map)
                .findOne());
    }

    /**
     * Rewrites a running request's answer without settling it; a settled row is left alone.
     *
     * @return whether a running row was updated
     */
    public boolean progress(final long id, final Object answer) {
        return move(id, InboxStatus.RUNNING, InboxStatus.RUNNING, Objects.requireNonNull(answer, "answer"))
                .isPresent();
    }

    /**
     * Settles a running request; a row that is no longer running stays as it is.
     *
     * @return the settled row, or empty when it was not running
     */
    public Optional<Request<P>> settle(final long id, final Outcome outcome) {
        return switch (Objects.requireNonNull(outcome, "outcome")) {
            case Outcome.Done done -> move(id, InboxStatus.RUNNING, InboxStatus.DONE, done.answer());
            case Outcome.Failed failed -> move(id, InboxStatus.RUNNING, InboxStatus.FAILED, failed.answer());
            case Outcome.Refused refused ->
                move(id, InboxStatus.RUNNING, InboxStatus.REFUSED, Json.tree(StoredRefusal.write(refused.refusal())));
        };
    }

    /**
     * Withdraws a request that has not been claimed.
     *
     * @param answer what to record, such as who withdrew it, or {@code null}
     * @return the cancelled row, or empty when it was claimed or settled already
     */
    public Optional<Request<P>> cancel(final long id, final @Nullable Object answer) {
        return move(id, InboxStatus.PENDING, InboxStatus.CANCELLED, answer);
    }

    private Optional<Request<P>> move(
            final long id, final InboxStatus from, final InboxStatus to, final @Nullable Object answer) {
        return jdbi.withHandle(handle -> handle.createQuery(MOVE)
                .define("table", table.name())
                .bind("id", id)
                .bind("from", from.name())
                .bind("to", to.name())
                .bind("settles", to.settled())
                .bind("outcome", answer == null ? null : DatabaseJson.encode(answer))
                .bind("channel", table.channel().sqlName())
                .map(this::map)
                .findOne());
    }

    /**
     * Gives up on every pending request whose patience has run out; the consumer's drain calls it.
     *
     * @return how many rows this call expired
     */
    public int expireDue() {
        return count("""
                WITH expired AS (
                    UPDATE <table> SET status = 'EXPIRED', finished = now()
                    WHERE status = 'PENDING' AND expires <= now()
                    RETURNING id
                )
                SELECT count(*) FROM (SELECT pg_notify(:channel, '') FROM expired) AS notified
                """, Map.of());
    }

    /**
     * Fails every request left running, which only the one consumer may call, at its start; {@link #takeOver} does.
     *
     * @param answer what to write into those rows
     * @return how many there were
     */
    public int settleOrphans(final Object answer) {
        return settleOrphans(answer, List.of());
    }

    /**
     * Fails every request left running but the spared ones, which another process still carries out.
     *
     * @param answer what to write into those rows
     * @param spared the ids to leave running
     * @return how many there were
     */
    public int settleOrphans(final Object answer, final Collection<Long> spared) {
        return count(
                """
                WITH failed AS (
                    UPDATE <table> SET status = 'FAILED', finished = now(), outcome = cast(:outcome AS jsonb)
                    WHERE status = 'RUNNING' AND id <> ALL(cast(:spared AS bigint[]))
                    RETURNING id
                )
                SELECT count(*) FROM (SELECT pg_notify(:channel, '') FROM failed) AS notified
                """,
                Map.of(
                        "outcome", DatabaseJson.encode(Objects.requireNonNull(answer, "answer")),
                        "spared", spared.toArray(Long[]::new)));
    }

    /** Returns one row whole, as JSON, so it can outlive the table being replaced by a restore. */
    public Optional<String> carry(final long id) {
        return jdbi.withHandle(
                handle -> handle.createQuery("SELECT cast(to_jsonb(t) AS text) FROM <table> t WHERE id = :id")
                        .define("table", table.name())
                        .bind("id", id)
                        .mapTo(String.class)
                        .findOne());
    }

    /**
     * Fails every row a restored dump held open, then writes a carried row back and counts the ids on from it.
     * One transaction, so a restore never leaves two open runs or none.
     *
     * @param row what {@link #carry} returned
     * @param answer what to write into the rows the dump held open
     */
    public void putBack(final String row, final Object answer) {
        Objects.requireNonNull(row, "row");
        final String outcome = DatabaseJson.encode(Objects.requireNonNull(answer, "answer"));
        jdbi.useTransaction(handle -> {
            handle.createUpdate("""
                            UPDATE <table> SET status = 'FAILED', finished = now(), outcome = cast(:outcome AS jsonb)
                            WHERE status IN ('PENDING', 'RUNNING')
                            """)
                    .define("table", table.name())
                    .bind("outcome", outcome)
                    .execute();
            handle.createUpdate("DELETE FROM <table> WHERE id = cast(cast(:row AS jsonb) ->> 'id' AS bigint)")
                    .define("table", table.name())
                    .bind("row", row)
                    .execute();
            handle.createUpdate(
                            "INSERT INTO <table> SELECT * FROM jsonb_populate_record(null::<table>, cast(:row AS jsonb))")
                    .define("table", table.name())
                    .bind("row", row)
                    .execute();
            handle.createQuery("SELECT setval(pg_get_serial_sequence(:name, 'id'), (SELECT max(id) FROM <table>))")
                    .define("table", table.name())
                    .bind("name", table.name())
                    .mapTo(Long.class)
                    .one();
        });
    }

    /**
     * Deletes every settled request that finished longer ago than {@code age}; a pending row is work, not history.
     *
     * @return how many rows went
     */
    public int purge(final Duration age) {
        return jdbi.withHandle(handle -> handle.createUpdate("""
                        DELETE FROM <table>
                        WHERE status NOT IN ('PENDING', 'RUNNING')
                          AND finished < now() - make_interval(secs => cast(:age AS double precision))
                        """)
                .define("table", table.name())
                .bind("age", seconds(Objects.requireNonNull(age, "age")))
                .execute());
    }

    /** Returns one request, whatever state it is in, or empty if there is no such row; overdue reads as expired. */
    public Optional<Request<P>> find(final long id) {
        return jdbi.withHandle(handle -> handle.createQuery(READ + "WHERE id = :id")
                .define("table", table.name())
                .bind("id", id)
                .map(this::map)
                .findOne());
    }

    /** Returns the most recent requests of one kind, newest first. */
    public List<Request<P>> recent(final Class<? extends P> kind, final int limit) {
        return jdbi.withHandle(handle -> handle.createQuery(READ + "WHERE kind = :kind ORDER BY id DESC LIMIT :limit")
                .define("table", table.name())
                .bind("kind", table.kindOf(kind))
                .bind("limit", Math.max(1, limit))
                .map(this::map)
                .list());
    }

    /**
     * Returns a text that changes whenever a row of this inbox is written, or reads as expired from now on.
     *
     * Every write gives a row a newer {@code xmin}; a watcher compares two versions instead of reading the rows.
     */
    public String version() {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT coalesce(max(xmin::text::bigint), 0) || ':' || count(*) || ':'
                               || count(*) FILTER (WHERE status = 'PENDING' AND expires <= now())
                        FROM <table>
                        """)
                .define("table", table.name())
                .mapTo(String.class)
                .one());
    }

    /** Returns when the next pending request becomes due, or empty when there is none. */
    public Optional<Instant> nextDue() {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT min(scheduled_for) FROM <table> WHERE status = 'PENDING'
                        """)
                .define("table", table.name())
                .mapTo(OffsetDateTime.class)
                .findOne()
                .map(OffsetDateTime::toInstant));
    }

    /**
     * Expires what is overdue, then claims and answers every due request, oldest first; a re-entrant call does nothing.
     * A handler that throws settles its request {@code FAILED} with the message, never retried.
     *
     * @return how many requests were settled
     */
    public int drain(final Handler<P> handler) {
        Objects.requireNonNull(handler, "handler");
        if (!draining.compareAndSet(false, true)) {
            return 0;
        }
        try {
            final int expired = expireDue();
            if (expired > 0) {
                log.warn("{} request(s) in {} were never claimed in time and have been given up on", expired, table);
            }
            int handled = 0;
            for (Optional<Request<P>> claimed = claim(); claimed.isPresent(); claimed = claim()) {
                answer(claimed.get(), handler);
                handled++;
            }
            return handled;
        } finally {
            draining.set(false);
        }
    }

    private void answer(final Request<P> request, final Handler<P> handler) {
        Outcome outcome;
        try {
            outcome = handler.handle(request);
        } catch (final RuntimeException failure) {
            // The message, not the stack trace: a web interface reads the row.
            log.error("{} request {} ({}) failed", table, request.id(), request.kind(), failure);
            outcome = Outcome.failed(Map.of("error", String.valueOf(failure.getMessage())));
        }
        if (settle(request.id(), outcome).isEmpty()) {
            log.warn("{} request {} was no longer running when it was settled", table, request.id());
        }
    }

    /** Drains on every signal of the hub, on the hub's thread; a handler that takes long hands its work elsewhere. */
    public void listen(final SignalHub hub, final Handler<P> handler) {
        Objects.requireNonNull(handler, "handler");
        hub.on(table.channel(), "the " + table + " inbox", () -> drain(handler));
    }

    /** Answers one claimed request. */
    @FunctionalInterface
    public interface Handler<P> {

        /** Returns what became of the request; throwing settles it {@code FAILED}. */
        Outcome handle(Request<P> request);
    }

    private int count(final String sql, final Map<String, Object> bindings) {
        return jdbi.withHandle(handle -> {
            final var query = handle.createQuery(sql)
                    .define("table", table.name())
                    .bind("channel", table.channel().sqlName());
            bindings.forEach(query::bind);
            return query.mapTo(Integer.class).one();
        });
    }

    private Request<P> map(final ResultSet row, final StatementContext context) throws SQLException {
        final String kind = row.getString("kind");
        final Class<? extends P> type = table.typeOf(kind)
                .orElseThrow(
                        () -> new IllegalStateException(table + " holds a kind this build does not know: " + kind));
        return new Request<>(
                row.getLong("id"),
                kind,
                table.payloads().cast(DatabaseJson.decode(row.getString("payload"), type)),
                InboxStatus.valueOf(row.getString("status")),
                Actor.of(row.getString("actor_kind"), row.getString("actor_id")),
                Objects.requireNonNull(instant(row.getTimestamp("requested")), "requested"),
                Objects.requireNonNull(instant(row.getTimestamp("scheduled_for")), "scheduled_for"),
                instant(row.getTimestamp("expires")),
                instant(row.getTimestamp("started")),
                instant(row.getTimestamp("finished")),
                row.getString("outcome"));
    }

    private static @Nullable Instant instant(final @Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static double seconds(final Duration duration) {
        return duration.toNanos() / 1_000_000_000.0;
    }
}
