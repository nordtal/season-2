package eu.nordtal.season.database.inbox;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.time.Backoff;
import eu.nordtal.season.common.time.Waiting;
import eu.nordtal.season.database.DatabaseJson;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.Journal;
import eu.nordtal.season.database.notify.SignalHub;
import eu.nordtal.season.messages.Refusal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.Query;
import org.jdbi.v3.core.statement.SqlStatement;
import org.jdbi.v3.core.statement.Update;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One consumer's inbox: submit, claim, progress, settle, expire and cancel, each announced on the table's channel.
 * A notification is not the state: a woken consumer claims until nothing is due. It borrows its pool.
 */
public final class Inbox<P> {

    private static final Logger log = LoggerFactory.getLogger(Inbox.class);

    /** How often {@link #ask} looks for the answer. */
    private static final Backoff POLL = Backoff.fixed(Duration.ofMillis(200));

    /** Writes a request and announces it in one statement, so a notification exists only for a committed row. */
    private static final String SUBMIT = """
            WITH inserted AS (
                INSERT INTO <table> (kind, <payloadColumn> actor_kind, actor_id, scheduled_for, expires)
                VALUES (:kind, <payloadValue> :actorKind, :actorId,
                        now() + make_interval(secs => cast(:delay AS double precision)),
                        now() + make_interval(secs => cast(:delay AS double precision) + cast(:patience AS double precision)))
                RETURNING *
            )
            SELECT inserted.*, pg_notify(:channel, '') AS notified FROM inserted
            """;

    /** Writes a row that nobody asked for, already settled and begun {@code took} seconds ago, and announces it. */
    private static final String RECORD = """
            WITH inserted AS (
                INSERT INTO <table> (kind, <payloadColumn> actor_kind, actor_id, status,
                                     requested, scheduled_for, started, finished <outcomeColumn>)
                SELECT :kind, <payloadValue> :actorKind, :actorId, :status,
                       began, began, began, now() <outcome>
                FROM (SELECT now() - make_interval(secs => cast(:took AS double precision)) AS began) AS run
                RETURNING *
            )
            SELECT inserted.*, pg_notify(:channel, '') AS notified FROM inserted
            """;

    /** Takes the oldest due row. {@code SKIP LOCKED} stops two consumers from claiming one row. */
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

    /** Moves one row from one status to another, and only from that one. */
    private static final String MOVE = """
            WITH moved AS (
                UPDATE <table>
                SET status = :to,
                    finished = CASE WHEN :settles THEN now() END,
                    started = CASE WHEN :to = 'PENDING' THEN NULL ELSE started END
                    <outcome>
                WHERE id = :id AND status = :from
                RETURNING *
            )
            SELECT moved.*, pg_notify(:channel, '') AS notified FROM moved
            """;

    /** Selects every column and reads a pending row past its expiry as expired. */
    private static final String READ = """
            SELECT id, kind, <payloadColumn>
                   CASE WHEN status = 'PENDING' AND expires <= now() THEN 'EXPIRED' ELSE status END AS status,
                   actor_kind, actor_id, requested, scheduled_for, expires, started, finished <outcomeColumn>
            FROM <table>
            """;

    private final Jdbi jdbi;
    private final InboxTable<P> table;
    private final KindRows rows;
    private final AtomicBoolean draining = new AtomicBoolean();

    private Inbox(final DataSource dataSource, final InboxTable<P> table) {
        this.jdbi = Jdbis.over(Objects.requireNonNull(dataSource, "dataSource"));
        this.table = Objects.requireNonNull(table, "table");
        this.rows = new KindRows(table);
    }

    /** Returns the inbox over {@code table}, which borrows a pool that somebody else closes. */
    public static <P> Inbox<P> over(final DataSource dataSource, final InboxTable<P> table) {
        return new Inbox<>(dataSource, table);
    }

    /**
     * Returns the inbox of the one consumer at its start and fails what its last start left running.
     *
     * @param consumer how the log names it: "the bot", "smp"
     */
    public static <P> Inbox<P> takeOver(final DataSource dataSource, final InboxTable<P> table, final String consumer) {
        Objects.requireNonNull(consumer, "consumer");
        final Inbox<P> inbox = over(dataSource, table);
        final int orphans = inbox.settleOrphans(null);
        if (orphans > 0) {
            log.warn("{} request(s) in {} were left running by the last start of {}", orphans, table, consumer);
        }
        return inbox;
    }

    public InboxTable<P> table() {
        return table;
    }

    /** Writes a request that is due at once and wakes the consumer. */
    public Request<P> submit(final P payload, final Actor actor) {
        return submit(payload, actor, Schedule.NOW);
    }

    /** Writes a request for its time and wakes the consumer. */
    public Request<P> submit(final P payload, final Actor actor, final Schedule schedule) {
        return jdbi.inTransaction(handle -> insert(handle, payload, actor, schedule));
    }

    /**
     * Writes a request that expires after {@code patience} and waits that long for it to be settled.
     * A request still open then is withdrawn and the answer is empty.
     */
    public Optional<Request<P>> ask(final P payload, final Actor actor, final Duration patience, final Waiting clock) {
        final long id = submit(payload, actor, Schedule.within(patience)).id();
        final Optional<Request<P>> answered = clock.until(
                () -> find(id).filter(
                                row -> row.status() != InboxStatus.PENDING && row.status() != InboxStatus.RUNNING),
                patience,
                POLL);
        if (answered.isEmpty()) {
            cancel(id, null);
        }
        return answered;
    }

    /** Writes a request and its journal line in one transaction. */
    public Request<P> submit(final P payload, final Actor actor, final Schedule schedule, final AuditLine journal) {
        Objects.requireNonNull(journal, "journal");
        return jdbi.inTransaction(handle -> {
            Journal.write(handle, journal);
            return insert(handle, payload, actor, schedule);
        });
    }

    /** Writes a request in the caller's transaction. The consumer wakes when that transaction commits. */
    public Request<P> submitWithin(final Handle handle, final P payload, final Actor actor) {
        return insert(Objects.requireNonNull(handle, "handle"), payload, actor, Schedule.NOW);
    }

    private Request<P> insert(final Handle handle, final P payload, final Actor actor, final Schedule schedule) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(schedule, "schedule");
        final Duration patience = schedule.patience();
        final Request<P> request = withPayload(handle.createQuery(SUBMIT), payload)
                .define("table", table.name())
                .bind("kind", table.kindOf(payload))
                .bind("actorKind", actor.kind().name())
                .bind("actorId", actor.id())
                .bind("delay", seconds(schedule.delay()))
                .bind("patience", patience == null ? null : seconds(patience))
                .bind("channel", table.channel().sqlName())
                .map((row, context) -> map(row, handle, payload))
                .one();
        rows.insertRow(handle, request.id(), payload);
        return request;
    }

    /** Binds the payload as JSON for a kind that is not a row. */
    private Query withPayload(final Query query, final P payload) {
        if (!table.hasPayload()) {
            return query.define("payloadColumn", "").define("payloadValue", "");
        }
        return query.define("payloadColumn", "payload,")
                .define("payloadValue", "cast(:payload AS jsonb),")
                .bind("payload", table.isRow(payload.getClass()) ? null : DatabaseJson.encode(payload));
    }

    /** Writes a server's refusal as a row of the refusal table: its reason and each of its values in a column. */
    private void refuse(final Handle handle, final long id, final Refusal refusal) {
        if (!(refusal.reason() instanceof ServerRefusal reason)) {
            throw new IllegalArgumentException(table + " keeps a server's refusal only, not " + refusal.reason());
        }
        final StringBuilder columns = new StringBuilder("request, reason");
        final StringBuilder values = new StringBuilder(":request, :reason");
        for (final String name : reason.arguments()) {
            columns.append(", ").append(name);
            values.append(", :").append(name);
        }
        final Update insert = handle.createUpdate("INSERT INTO <refusal> (" + columns + ") VALUES (" + values + ")")
                .define("refusal", table.refusalTable())
                .bind("request", id)
                .bind("reason", reason.name());
        reason.arguments()
                .forEach(name -> insert.bind(name, refusal.message().args().get(name)));
        insert.execute();
    }

    /** Reads the refusal of a refused row back, worded again from its reason and values. */
    private @Nullable Refusal refusal(final Handle handle, final long id) {
        return handle.createQuery("SELECT * FROM <refusal> WHERE request = :id")
                .define("refusal", table.refusalTable())
                .bind("id", id)
                .map((row, context) -> {
                    final ServerRefusal reason = ServerRefusal.valueOf(row.getString("reason"));
                    final Map<String, Object> values = new HashMap<>();
                    for (final String name : reason.arguments()) {
                        values.put(name, row.getObject(name));
                    }
                    return reason.with(values);
                })
                .findOne()
                .orElse(null);
    }

    /** Claims the oldest due request and marks it running. Call it until empty, as one signal may stand for many. */
    public Optional<Request<P>> claim() {
        return jdbi.withHandle(handle -> handle.createQuery(CLAIM)
                .define("table", table.name())
                .bind("channel", table.channel().sqlName())
                .map(mapper(handle))
                .findOne());
    }

    /**
     * Rewrites the answer of a running request without settling it. A settled row stays.
     *
     * @return whether a running row was updated
     */
    public boolean progress(final long id, final Object answer) {
        Objects.requireNonNull(answer, "answer");
        return jdbi.inTransaction(handle -> move(handle, id, InboxStatus.RUNNING, InboxStatus.RUNNING, answer))
                .isPresent();
    }

    /**
     * Settles a running request. A row that is not running stays.
     *
     * @return the settled row, or empty if it was not running
     */
    public Optional<Request<P>> settle(final long id, final Outcome outcome) {
        final Settled settled = Settled.of(outcome, table);
        return jdbi.inTransaction(handle -> move(handle, id, InboxStatus.RUNNING, settled.status(), settled.json())
                .map(moved -> written(handle, moved, outcome)));
    }

    /**
     * Writes a request that its consumer carried out unasked, already settled, and announces it.
     *
     * @param took how long it ran: request and start lie that far before now on the database's clock
     */
    public Request<P> record(final P payload, final Actor actor, final Outcome outcome, final Duration took) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(actor, "actor");
        final Settled settled = Settled.of(outcome, table);
        return jdbi.inTransaction(handle -> {
            final Request<P> request = withOutcome(
                            withPayload(handle.createQuery(RECORD), payload),
                            ", cast(:outcome AS jsonb)",
                            settled.json())
                    .define("table", table.name())
                    .bind("kind", table.kindOf(payload))
                    .bind("actorKind", actor.kind().name())
                    .bind("actorId", actor.id())
                    .bind("status", settled.status().name())
                    .bind("took", seconds(Objects.requireNonNull(took, "took")))
                    .bind("channel", table.channel().sqlName())
                    .map((row, context) -> map(row, handle, payload))
                    .one();
            rows.insertRow(handle, request.id(), payload);
            return written(handle, request, outcome);
        });
    }

    /** The status an outcome settles a row in, and what goes into the outcome column. */
    private record Settled(InboxStatus status, @Nullable Object json) {

        static Settled of(final Outcome outcome, final InboxTable<?> table) {
            return switch (Objects.requireNonNull(outcome, "outcome")) {
                case Outcome.Done done ->
                    new Settled(
                            InboxStatus.DONE,
                            done.answer() == null
                                            || table.isAnswer(done.answer().getClass())
                                    ? null
                                    : done.answer());
                case Outcome.Failed failed -> new Settled(InboxStatus.FAILED, failed.answer());
                case Outcome.Refused _ -> new Settled(InboxStatus.REFUSED, null);
            };
        }
    }

    /** Writes the typed part of an outcome beside the row it settled and reads the row again. */
    private Request<P> written(final Handle handle, final Request<P> settled, final Outcome outcome) {
        final Class<?> kind = settled.payload().getClass();
        final Optional<Class<?>> answers = table.answerOf(kind);
        if (outcome instanceof Outcome.Refused refused) {
            refuse(handle, settled.id(), refused.refusal());
        } else if (outcome instanceof Outcome.Done(final Object answer)
                && (answers.isPresent() || (answer != null && table.isAnswer(answer.getClass())))) {
            if (answers.isEmpty() || !answers.get().isInstance(answer)) {
                throw new IllegalArgumentException(settled.kind() + " in " + table + " is not answered with " + answer);
            }
            rows.answer(handle, settled.id(), kind, answer);
        } else {
            return settled;
        }
        return read(handle, settled.id()).orElseThrow();
    }

    /**
     * Withdraws a request that nobody claimed.
     *
     * @param answer what to record, such as who withdrew it. May be null
     * @return the cancelled row, or empty if it was claimed or settled
     */
    public Optional<Request<P>> cancel(final long id, final @Nullable Object answer) {
        return jdbi.inTransaction(handle -> move(handle, id, InboxStatus.PENDING, InboxStatus.CANCELLED, answer));
    }

    private Optional<Request<P>> move(
            final Handle handle,
            final long id,
            final InboxStatus from,
            final InboxStatus to,
            final @Nullable Object json) {
        return withOutcome(handle.createQuery(MOVE), ", outcome = coalesce(cast(:outcome AS jsonb), outcome)", json)
                .define("table", table.name())
                .bind("id", id)
                .bind("from", from.name())
                .bind("to", to.name())
                .bind("settles", to.settled())
                .bind("channel", table.channel().sqlName())
                .map(mapper(handle))
                .findOne();
    }

    /**
     * Defines {@code <outcome>} as {@code sql}, which binds the JSON of the outcome column, in a table that has one.
     *
     * @throws IllegalArgumentException if {@code json} is given for a table without that column
     */
    private <S extends SqlStatement<S>> S withOutcome(
            final S statement, final String sql, final @Nullable Object json) {
        if (!table.hasOutcome()) {
            if (json != null) {
                throw new IllegalArgumentException(table + " keeps no answer as JSON, so not " + json);
            }
            return statement.define("outcome", "").define("outcomeColumn", "");
        }
        return statement
                .define("outcome", sql)
                .define("outcomeColumn", ", outcome")
                .bind("outcome", json == null ? null : DatabaseJson.encode(json));
    }

    /** Expires every pending request past its patience and returns how many. */
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
     * Fails every request left running, at the start of the one consumer. {@link #takeOver} calls it.
     *
     * @param answer what to write into the outcome column of those rows, null for nothing
     * @return the rows failed
     */
    public int settleOrphans(final @Nullable Object answer) {
        return settleOrphans(answer, List.of());
    }

    /**
     * Fails every request left running except the spared ones, which another process still runs.
     *
     * @param answer what to write into the outcome column of those rows, null for nothing
     * @param spared the ids to leave running
     * @return the rows failed
     */
    public int settleOrphans(final @Nullable Object answer, final Collection<Long> spared) {
        return jdbi.withHandle(
                handle -> withOutcome(handle.createQuery("""
                                WITH failed AS (
                                    UPDATE <table> SET status = 'FAILED', finished = now() <outcome>
                                    WHERE status = 'RUNNING' AND id <> ALL(cast(:spared AS bigint[]))
                                    RETURNING id
                                )
                                SELECT count(*) FROM (SELECT pg_notify(:channel, '') FROM failed) AS notified
                                """), ", outcome = cast(:outcome AS jsonb)", answer)
                        .define("table", table.name())
                        .bind("channel", table.channel().sqlName())
                        .bind("spared", spared.toArray(Long[]::new))
                        .mapTo(Integer.class)
                        .one());
    }

    /** Returns one row as JSON, so it can outlive a table that a restore replaces. */
    public Optional<String> carry(final long id) {
        return jdbi.withHandle(
                handle -> handle.createQuery("SELECT cast(to_jsonb(t) AS text) FROM <table> t WHERE id = :id")
                        .define("table", table.name())
                        .bind("id", id)
                        .mapTo(String.class)
                        .findOne());
    }

    /**
     * Fails every row that a restored dump held open, writes a carried row back and counts the ids on from it.
     * It runs in one transaction, so a restore never leaves two open runs or none.
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
            // The proxy and the agent may hold a countdown that the dump does not know, so they read again.
            handle.createQuery("SELECT pg_notify(:channel, '') IS NULL")
                    .bind("channel", table.channel().sqlName())
                    .mapTo(Boolean.class)
                    .one();
            handle.createQuery("SELECT setval(pg_get_serial_sequence(:name, 'id'), (SELECT max(id) FROM <table>))")
                    .define("table", table.name())
                    .bind("name", table.name())
                    .mapTo(Long.class)
                    .one();
        });
    }

    /**
     * Deletes every settled request that finished longer ago than {@code age}. A pending row stays.
     *
     * @return the rows deleted
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

    /** Returns one request in any state, or empty if the row does not exist. An overdue row reads as expired. */
    public Optional<Request<P>> find(final long id) {
        return jdbi.withHandle(handle -> read(handle, id));
    }

    private Optional<Request<P>> read(final Handle handle, final long id) {
        return reading(handle, "WHERE id = :id")
                .bind("id", id)
                .map(mapper(handle))
                .findOne();
    }

    private Query reading(final Handle handle, final String where) {
        return handle.createQuery(READ + where)
                .define("table", table.name())
                .define("payloadColumn", table.hasPayload() ? "payload," : "")
                .define("outcomeColumn", table.hasOutcome() ? ", outcome" : "");
    }

    public List<Request<P>> recent(final Class<? extends P> kind, final int limit) {
        return jdbi.withHandle(handle -> reading(handle, "WHERE kind = :kind ORDER BY id DESC LIMIT :limit")
                .bind("kind", table.kindOf(kind))
                .bind("limit", Math.max(1, limit))
                .map(mapper(handle))
                .list());
    }

    /**
     * Returns a text that changes whenever a row of this inbox is written or starts to read as expired.
     * Every write gives a row a newer {@code xmin}, so a watcher compares versions instead of reading rows.
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

    /** Returns when the next pending request is due, or empty if none is. */
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
     * Expires overdue requests, then claims and answers every due request, oldest first.
     * A re-entrant call does nothing. A handler that throws fails its request, never retried, and the log has why.
     *
     * @return the requests settled
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
            log.error("{} request {} ({}) failed", table, request.id(), request.kind(), failure);
            outcome = Outcome.failed(null);
        }
        if (settle(request.id(), outcome).isEmpty()) {
            log.warn("{} request {} was no longer running when it was settled", table, request.id());
        }
    }

    /** Drains on every signal of the hub, on its thread. A slow handler passes its work on. */
    public void listen(final SignalHub hub, final Handler<P> handler) {
        Objects.requireNonNull(handler, "handler");
        hub.on(table.channel(), "the " + table + " inbox", () -> drain(handler));
    }

    /** Answers one claimed request. */
    @FunctionalInterface
    public interface Handler<P> {

        /** Returns the outcome. A throw settles the request {@code FAILED}. */
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

    private RowMapper<Request<P>> mapper(final Handle handle) {
        return (row, context) -> map(row, handle, null);
    }

    /** Reads a row with the payload {@code known} if given, else from the row of its kind or its JSON. */
    private Request<P> map(final ResultSet row, final Handle handle, final @Nullable P known) throws SQLException {
        final long id = row.getLong("id");
        final String kind = row.getString("kind");
        final Class<? extends P> type = table.typeOf(kind)
                .orElseThrow(
                        () -> new IllegalStateException(table + " holds a kind this build does not know: " + kind));
        final Object payload = known != null
                ? known
                : table.isRow(type)
                        ? rows.readRow(handle, id, type, type)
                        : DatabaseJson.decode(row.getString("payload"), type);
        final InboxStatus status = InboxStatus.valueOf(row.getString("status"));
        final Optional<Class<?>> answers = table.answerOf(type);
        return new Request<>(
                id,
                kind,
                table.payloads().cast(payload),
                status,
                Actor.of(row.getString("actor_kind"), row.getString("actor_id")),
                Objects.requireNonNull(instant(row.getTimestamp("requested")), "requested"),
                Objects.requireNonNull(instant(row.getTimestamp("scheduled_for")), "scheduled_for"),
                instant(row.getTimestamp("expires")),
                instant(row.getTimestamp("started")),
                instant(row.getTimestamp("finished")),
                table.hasOutcome() ? row.getString("outcome") : null,
                status == InboxStatus.DONE && answers.isPresent()
                        ? rows.readRow(handle, id, type, answers.get())
                        : null,
                status == InboxStatus.REFUSED ? refusal(handle, id) : null);
    }

    private static @Nullable Instant instant(final @Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static double seconds(final Duration duration) {
        return duration.toNanos() / 1_000_000_000.0;
    }
}
