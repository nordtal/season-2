package eu.nordtal.season.database.inbox;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.DatabaseJson;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.Journal;
import eu.nordtal.season.database.notify.SignalHub;
import java.lang.reflect.RecordComponent;
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
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.Query;
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
                                     requested, scheduled_for, started, finished, outcome)
                SELECT :kind, <payloadValue> :actorKind, :actorId, :status,
                       began, began, began, now(), cast(:outcome AS jsonb)
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
                    started = CASE WHEN :to = 'PENDING' THEN NULL ELSE started END,
                    outcome = coalesce(cast(:outcome AS jsonb), outcome)
                WHERE id = :id AND status = :from
                RETURNING *
            )
            SELECT moved.*, pg_notify(:channel, '') AS notified FROM moved
            """;

    /** Selects every column and reads a pending row past its expiry as expired. */
    private static final String READ = """
            SELECT id, kind, <payloadColumn>
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

    /** Returns the inbox over {@code table}, which borrows a pool that somebody else closes. */
    public static <P> Inbox<P> over(final DataSource dataSource, final InboxTable<P> table) {
        return new Inbox<>(dataSource, table);
    }

    /**
     * Returns the inbox of the one consumer at its start and fails what its last start left running.
     *
     * @param consumer how a sentence names it: "the bot", "smp"
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
        insertRow(handle, request.id(), payload);
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

    private void insertRow(final Handle handle, final long id, final P payload) {
        if (!table.isRow(payload.getClass())) {
            return;
        }
        final RecordComponent[] components = payload.getClass().getRecordComponents();
        final StringBuilder columns = new StringBuilder("request");
        final StringBuilder values = new StringBuilder(":request");
        for (final RecordComponent component : components) {
            columns.append(", ").append(InboxTable.column(component));
            values.append(", :").append(component.getName());
        }
        final Update insert = handle.createUpdate("INSERT INTO <row> (" + columns + ") VALUES (" + values + ")")
                .define("row", table.rowTable(payload.getClass()))
                .bind("request", id);
        for (final RecordComponent component : components) {
            insert.bindByType(component.getName(), component(component, payload), component.getGenericType());
        }
        insert.execute();
    }

    private static @Nullable Object component(final RecordComponent component, final Object record) {
        try {
            return component.getAccessor().invoke(record);
        } catch (final ReflectiveOperationException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private <T> T readRow(final Handle handle, final long id, final Class<T> type) {
        return handle.createQuery("SELECT * FROM <row> WHERE request = :id")
                .define("row", table.rowTable(type))
                .bind("id", id)
                .map((row, context) -> {
                    final RecordComponent[] components = type.getRecordComponents();
                    final Class<?>[] types = new Class<?>[components.length];
                    final Object[] values = new Object[components.length];
                    for (int i = 0; i < components.length; i++) {
                        final RecordComponent component = components[i];
                        types[i] = component.getType();
                        values[i] = context.findColumnMapperFor(component.getGenericType())
                                .orElseThrow(() -> new IllegalStateException("no column mapper for " + component))
                                .map(row, InboxTable.column(component), context);
                    }
                    try {
                        return type.getDeclaredConstructor(types).newInstance(values);
                    } catch (final ReflectiveOperationException failure) {
                        throw new IllegalStateException(table + " request " + id + " reads as no " + type, failure);
                    }
                })
                .findOne()
                .orElseThrow(() ->
                        new IllegalStateException(table + " request " + id + " has no row in " + table.rowTable(type)));
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
        return move(id, InboxStatus.RUNNING, InboxStatus.RUNNING, Objects.requireNonNull(answer, "answer"))
                .isPresent();
    }

    /**
     * Settles a running request. A row that is not running stays.
     *
     * @return the settled row, or empty if it was not running
     */
    public Optional<Request<P>> settle(final long id, final Outcome outcome) {
        final Settled settled = Settled.of(outcome);
        return move(id, InboxStatus.RUNNING, settled.status(), settled.answer());
    }

    /**
     * Writes a request that its consumer carried out unasked, already settled, and announces it.
     *
     * @param took how long it ran: request and start lie that far before now on the database's clock
     */
    public Request<P> record(final P payload, final Actor actor, final Outcome outcome, final Duration took) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(actor, "actor");
        final Settled settled = Settled.of(outcome);
        return jdbi.inTransaction(handle -> {
            final Request<P> request = withPayload(handle.createQuery(RECORD), payload)
                    .define("table", table.name())
                    .bind("kind", table.kindOf(payload))
                    .bind("actorKind", actor.kind().name())
                    .bind("actorId", actor.id())
                    .bind("status", settled.status().name())
                    .bind("outcome", settled.answer() == null ? null : DatabaseJson.encode(settled.answer()))
                    .bind("took", seconds(Objects.requireNonNull(took, "took")))
                    .bind("channel", table.channel().sqlName())
                    .map((row, context) -> map(row, handle, payload))
                    .one();
            insertRow(handle, request.id(), payload);
            return request;
        });
    }

    /** The status an outcome settles a row in, and the answer written. */
    private record Settled(InboxStatus status, @Nullable Object answer) {

        static Settled of(final Outcome outcome) {
            return switch (Objects.requireNonNull(outcome, "outcome")) {
                case Outcome.Done done -> new Settled(InboxStatus.DONE, done.answer());
                case Outcome.Failed failed -> new Settled(InboxStatus.FAILED, failed.answer());
                case Outcome.Refused refused ->
                    new Settled(InboxStatus.REFUSED, Json.tree(StoredRefusal.write(refused.refusal())));
            };
        }
    }

    /**
     * Withdraws a request that nobody claimed.
     *
     * @param answer what to record, such as who withdrew it. May be null
     * @return the cancelled row, or empty if it was claimed or settled
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
                .map(mapper(handle))
                .findOne());
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
     * @param answer what to write into those rows
     * @return the rows failed
     */
    public int settleOrphans(final Object answer) {
        return settleOrphans(answer, List.of());
    }

    /**
     * Fails every request left running except the spared ones, which another process still runs.
     *
     * @param answer what to write into those rows
     * @param spared the ids to leave running
     * @return the rows failed
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
        return jdbi.withHandle(handle -> handle.createQuery(READ + "WHERE id = :id")
                .define("table", table.name())
                .define("payloadColumn", table.hasPayload() ? "payload," : "")
                .bind("id", id)
                .map(mapper(handle))
                .findOne());
    }

    public List<Request<P>> recent(final Class<? extends P> kind, final int limit) {
        return jdbi.withHandle(handle -> handle.createQuery(READ + "WHERE kind = :kind ORDER BY id DESC LIMIT :limit")
                .define("table", table.name())
                .define("payloadColumn", table.hasPayload() ? "payload," : "")
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
     * A re-entrant call does nothing. A handler that throws fails its request with the message, never retried.
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
            // The message without the stack trace, as a web interface reads the row.
            log.error("{} request {} ({}) failed", table, request.id(), request.kind(), failure);
            outcome = Outcome.failed(Map.of("error", String.valueOf(failure.getMessage())));
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
                : table.isRow(type) ? readRow(handle, id, type) : DatabaseJson.decode(row.getString("payload"), type);
        return new Request<>(
                id,
                kind,
                table.payloads().cast(payload),
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
