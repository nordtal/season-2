package eu.nordtal.season.database.journal;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.Jdbis;
import eu.nordtal.season.database.JournalDetail.FailedStep;
import eu.nordtal.season.database.alert.AlertBook;
import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.database.alert.RaisedAlert;
import eu.nordtal.season.database.audit.AuditDirectory;
import eu.nordtal.season.database.audit.AuditEntry;
import eu.nordtal.season.database.journal.JournalSearch.Term;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateStatus;
import eu.nordtal.season.messages.MessageRef;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Query;
import org.jspecify.annotations.Nullable;

/**
 * Implements {@link JournalStream} with one query over entries and runs, which each term narrows on both sides.
 * The rows of the page are then read whole by the journal, the alert book and a query of runs.
 */
final class JdbiJournalStream implements JournalStream {

    /** The people a name, a Discord id or a player UUID stands for, as Discord ids. */
    private static final String PEOPLE_NAMED = """
            (SELECT u.discord_id FROM discord_user u
             WHERE starts_with(lower(u.discord_username), lower(:%1$s))
                OR starts_with(lower(u.discord_display_name), lower(:%1$s))
             UNION SELECT l.discord_id FROM account_link l WHERE starts_with(lower(l.mc_name), lower(:%1$s)))""";

    private static final String PEOPLE_LINKED =
            "(SELECT l.discord_id FROM account_link l WHERE l.mc_uuid = cast(:%s AS uuid))";

    /** The start of the problem an alert raises or clears: its first raise since the all-clear before it. */
    private static final String OPENED = """
            SELECT e.id, (SELECT min(o.occurred) FROM journal_entry o
                          WHERE o.action = e.action AND o.level IN ('WARN', 'DOWN') AND o.occurred <= e.occurred
                            AND o.occurred > coalesce((SELECT max(c.occurred) FROM journal_entry c
                                                       WHERE c.action = e.action AND c.level = 'OK'
                                                         AND c.occurred < e.occurred), '-infinity')) AS opened
            FROM journal_entry e WHERE e.id = ANY(:ids)
            """;

    private static final String RUNS = """
            SELECT r.id, r.kind, r.status, r.actor_kind, r.actor_id,
                   ARRAY(SELECT s.service FROM run_service s WHERE s.run = r.id ORDER BY s.service) AS services
            FROM steward_inbox r WHERE r.id = ANY(:ids)
            """;

    private final Jdbi jdbi;
    private final AuditDirectory journal;
    private final AlertBook alerts;

    JdbiJournalStream(final DataSource dataSource) {
        this.jdbi = Jdbis.over(Objects.requireNonNull(dataSource, "dataSource"));
        this.journal = AuditDirectory.using(dataSource);
        this.alerts = AlertBook.using(dataSource);
    }

    @Override
    public Set<String> services() {
        return jdbi.withHandle(
                handle -> handle.createQuery("""
                        SELECT service FROM journal_entry WHERE service IS NOT NULL
                        UNION SELECT service FROM run_service""").mapTo(String.class).collect(Collectors.toSet()));
    }

    @Override
    public Page page(final List<Term> terms, final @Nullable String after, final int limit) {
        final int count = Math.max(1, limit);
        final List<Row> rows = jdbi.withHandle(handle -> rows(handle, terms, after, count + 1));
        final List<Row> shown = rows.subList(0, Math.min(count, rows.size()));
        final List<JournalItem> items = items(shown);
        final Row last = shown.isEmpty() ? null : shown.getLast();
        return new Page(items, rows.size() > count && last != null ? last.occurred() + "_" + last.key() : null);
    }

    private static List<Row> rows(
            final Handle handle, final List<Term> terms, final @Nullable String after, final int limit) {
        final List<String> entries = new ArrayList<>();
        final List<String> runs = new ArrayList<>();
        for (int index = 0; index < terms.size(); index++) {
            final String name = "t" + index;
            entries.add("AND (" + entryClause(terms.get(index).kind(), name) + ")");
            runs.add("AND (" + runClause(terms.get(index).kind(), name) + ")");
        }
        final Query query = handle.createQuery("""
                SELECT s.key, s.occurred, s.entry, s.alert, s.run FROM (
                    SELECT 'e' || e.id AS key, e.occurred, e.id AS entry, e.level IS NOT NULL AS alert, NULL AS run
                    FROM journal_entry e
                             LEFT JOIN payment_request p ON p.id = e.payment
                             LEFT JOIN journal_grant g ON g.entry = e.id
                             LEFT JOIN journal_booking b ON b.entry = e.id
                             LEFT JOIN steward_inbox r ON r.id = e.run
                    WHERE NOT (e.run IS NOT NULL AND e.action IN ('REQUEST_RUN', 'RUN_FAILED')) %s
                    UNION ALL
                    SELECT 'r' || lpad(r.id::text, 19, '0'), coalesce(r.finished, r.requested), NULL, false, r.id
                    FROM steward_inbox r
                    WHERE true %s
                ) s
                WHERE cast(:at AS timestamptz) IS NULL OR (s.occurred, s.key) < (cast(:at AS timestamptz), :key)
                ORDER BY s.occurred DESC, s.key DESC
                LIMIT :limit
                """.formatted(String.join(" ", entries), String.join(" ", runs)));
        for (int index = 0; index < terms.size(); index++) {
            bind(query, "t" + index, terms.get(index));
        }
        final int split = after == null ? -1 : after.indexOf('_');
        if (after != null && split < 0) {
            throw new IllegalArgumentException("a cursor is an instant and a key: " + after);
        }
        return query.bind("at", after == null ? null : Timestamp.from(Instant.parse(after.substring(0, split))))
                .bind("key", after == null ? "" : after.substring(split + 1))
                .bind("limit", limit)
                .map((row, context) -> new Row(
                        Objects.requireNonNull(row.getString("key"), "key"),
                        Objects.requireNonNull(row.getTimestamp("occurred"), "occurred")
                                .toInstant(),
                        row.getObject("entry", UUID.class),
                        row.getBoolean("alert"),
                        row.getObject("run", Long.class)))
                .list();
    }

    private static void bind(final Query query, final String name, final Term term) {
        switch (term.kind()) {
            case ACTION -> query.bindArray(name, String.class, term.values());
            case DAYS -> query.bind(name, Integer.parseInt(term.values().getFirst()));
            default -> query.bind(name, term.values().getFirst());
        }
    }

    /** Returns the clause that keeps an entry {@code e} that matches the term bound as {@code name}. */
    private static String entryClause(final Term.Kind kind, final String name) {
        final String value = ":" + name;
        return switch (kind) {
            case PAYMENT ->
                "p.reference = " + value
                        + " OR EXISTS (SELECT 1 FROM journal_bank_payment bp WHERE bp.entry = e.id AND bp.reference = "
                        + value + ")";
            case DISCORD -> "e.subject = " + value + " OR e.actor_id = " + value;
            case UUID ->
                "e.mc_uuid = cast(" + value + " AS uuid) OR e.subject IN " + PEOPLE_LINKED.formatted(name)
                        + " OR e.actor_id IN " + PEOPLE_LINKED.formatted(name);
            case NAME ->
                "e.subject IN " + PEOPLE_NAMED.formatted(name) + " OR e.actor_id IN " + PEOPLE_NAMED.formatted(name);
            case DAYS -> "coalesce(g.days, b.days) >= " + value;
            case SERVICE ->
                "e.service = " + value
                        + " OR EXISTS (SELECT 1 FROM alert_measure m WHERE m.entry = e.id AND m.subject = " + value
                        + ")"
                        + " OR EXISTS (SELECT 1 FROM run_service rs WHERE rs.run = e.run AND rs.service = " + value
                        + ")";
            case LEVEL -> "e.level = " + value;
            case RUN_KIND -> "r.kind = " + value;
            case RUN_STATUS -> "r.status = " + value;
            case GAUGE -> "EXISTS (SELECT 1 FROM alert_measure m WHERE m.entry = e.id AND m.gauge = " + value + ")";
            case ACTION -> "e.action = ANY(" + value + ")";
        };
    }

    /** Returns the clause that keeps a run {@code r} that matches the term bound as {@code name}. */
    private static String runClause(final Term.Kind kind, final String name) {
        final String value = ":" + name;
        return switch (kind) {
            case PAYMENT, DAYS, GAUGE -> "false";
            case DISCORD -> "r.actor_id = " + value;
            case UUID -> "r.actor_id IN " + PEOPLE_LINKED.formatted(name);
            case NAME -> "r.actor_id IN " + PEOPLE_NAMED.formatted(name);
            case SERVICE -> "EXISTS (SELECT 1 FROM run_service rs WHERE rs.run = r.id AND rs.service = " + value + ")";
            case LEVEL -> value + " = 'DOWN' AND r.status = 'FAILED'";
            case RUN_KIND -> "r.kind = " + value;
            case RUN_STATUS -> "r.status = " + value;
            // A run stands for the entry that asked for it and for the alert of its failure.
            case ACTION ->
                "'REQUEST_RUN' = ANY(" + value + ") OR 'RUN_FAILED' = ANY(" + value + ") AND r.status = 'FAILED'";
        };
    }

    private List<JournalItem> items(final List<Row> rows) {
        final Map<UUID, AuditEntry> entries = journal.entries(ids(rows, false)).stream()
                .collect(Collectors.toMap(AuditEntry::id, Function.identity()));
        final List<UUID> alertIds = ids(rows, true);
        final Map<UUID, RaisedAlert> raised =
                alerts.alerts(alertIds).stream().collect(Collectors.toMap(RaisedAlert::id, Function.identity()));
        final Map<UUID, Instant> opened = opened(alertIds);
        final Map<Long, RunWithActor> runs =
                runs(rows.stream().map(Row::run).filter(Objects::nonNull).toList());
        final List<JournalItem> items = new ArrayList<>();
        for (final Row row : rows) {
            final UUID id = row.entry();
            if (row.run() != null) {
                final RunWithActor run = Objects.requireNonNull(runs.get(row.run()), "run");
                items.add(new JournalItem(row.key(), row.occurred(), run.actor(), null, null, null, run.run()));
            } else if (row.alert()) {
                final RaisedAlert alert = Objects.requireNonNull(raised.get(id), "alert");
                final AlertType type = AlertType.of(alert.alert().action());
                items.add(new JournalItem(
                        row.key(),
                        row.occurred(),
                        alert.raisedBy(),
                        null,
                        alert,
                        type != null && type.measured() ? opened.get(id) : null,
                        null));
            } else {
                final AuditEntry entry = Objects.requireNonNull(entries.get(id), "entry");
                items.add(new JournalItem(row.key(), row.occurred(), entry.actor(), entry, null, null, null));
            }
        }
        return items;
    }

    private static List<UUID> ids(final List<Row> rows, final boolean alert) {
        return rows.stream()
                .filter(row -> row.entry() != null && row.alert() == alert)
                .map(Row::entry)
                .toList();
    }

    private Map<UUID, Instant> opened(final List<UUID> ids) {
        final Map<UUID, Instant> opened = new HashMap<>();
        if (!ids.isEmpty()) {
            jdbi.useHandle(handle -> handle.createQuery(OPENED)
                    .bindArray("ids", UUID.class, ids)
                    .map((row, context) -> {
                        final Timestamp at = row.getTimestamp("opened");
                        if (at != null) {
                            opened.put(Objects.requireNonNull(row.getObject("id", UUID.class)), at.toInstant());
                        }
                        return 0;
                    })
                    .list());
        }
        return opened;
    }

    private Map<Long, RunWithActor> runs(final List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        final Map<Long, List<MessageRef>> why = why(ids);
        return jdbi.withHandle(handle -> handle.createQuery(RUNS)
                .bindArray("ids", Long.class, ids)
                .map((row, context) -> {
                    final long id = row.getLong("id");
                    return new RunWithActor(
                            Actor.of(
                                    Objects.requireNonNull(row.getString("actor_kind"), "actor_kind"),
                                    row.getString("actor_id")),
                            new JournalRun(
                                    id,
                                    UpdateKind.valueOf(row.getString("kind")),
                                    UpdateStatus.fromDatabase(row.getString("status")),
                                    List.of((String[]) Objects.requireNonNull(row.getArray("services"), "services")
                                            .getArray()),
                                    why.getOrDefault(id, List.of())));
                })
                .collect(Collectors.toMap(run -> run.run().id(), Function.identity())));
    }

    private Map<Long, List<MessageRef>> why(final List<Long> ids) {
        return jdbi.withHandle(handle -> FailedStep.of(handle, ids)).entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        steps -> steps.getValue().stream().map(FailedStep::line).toList()));
    }

    /** One row of the stream before it is read whole: an entry, an alert or a run. */
    private record Row(
            String key,
            Instant occurred,
            @Nullable UUID entry,
            boolean alert,
            @Nullable Long run) {}

    private record RunWithActor(Actor actor, JournalRun run) {}
}
