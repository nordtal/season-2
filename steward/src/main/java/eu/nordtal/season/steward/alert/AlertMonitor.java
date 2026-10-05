package eu.nordtal.season.steward.alert;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.AlertBook;
import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateRequest;
import eu.nordtal.season.database.update.UpdateStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Measures the stack, keeps what is wrong for the pages, and raises an alert whenever a type changes.
 *
 * The first reading only sets the baseline, a failed run is raised once, and the thresholds are asked for anew.
 */
public final class AlertMonitor {

    /** The module name every alert of this process is raised under. */
    public static final String RAISED_BY = "steward";

    /** How far back a failed run is still raised, which covers a restart of this process. */
    static final Duration RUN_WINDOW = Duration.ofMinutes(15);

    private static final Logger log = LoggerFactory.getLogger(AlertMonitor.class);

    private final Supplier<StackReading> reading;
    private final Supplier<Thresholds> thresholds;
    private final AlertBook book;
    private final UpdateDirectory runs;
    private final Clock clock;

    private volatile Snapshot snapshot = new Snapshot(null, null, List.of());

    /** Null until the first reading; touched by the polling thread only. */
    private @Nullable Map<AlertType, Alert> last;

    public AlertMonitor(
            final Supplier<StackReading> reading,
            final Supplier<Thresholds> thresholds,
            final AlertBook book,
            final UpdateDirectory runs,
            final Clock clock) {
        this.reading = Objects.requireNonNull(reading, "reading");
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
        this.book = Objects.requireNonNull(book, "book");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * What is wrong right now, for the pages.
     *
     * @param checkedAt when the last reading succeeded, or null before the first
     * @param unreadable why the last reading failed, or null when it succeeded
     */
    public record Snapshot(
            @Nullable Instant checkedAt, @Nullable String unreadable, List<Alert> alerts) {

        public Snapshot {
            alerts = List.copyOf(alerts);
        }

        /** Returns the worst level, and yellow rather than green when nothing could be read. */
        public Alert.Level level() {
            if (alerts.stream().anyMatch(alert -> alert.level() == Alert.Level.DOWN)) {
                return Alert.Level.DOWN;
            }
            return alerts.isEmpty() && (unreadable == null && checkedAt != null) ? Alert.Level.OK : Alert.Level.WARN;
        }
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    /** Reads the stack once and raises every type that changed; never throws, since it runs on a timer. */
    public void poll() {
        final List<Alert> now;
        try {
            now = StackAlerts.of(reading.get(), thresholds.get(), clock.instant());
        } catch (final RuntimeException unreadable) {
            final Snapshot before = snapshot;
            snapshot = new Snapshot(before.checkedAt(), String.valueOf(unreadable.getMessage()), before.alerts());
            log.warn("Could not read the stack for the alerts this round: {}", unreadable.getMessage());
            return;
        }
        snapshot = new Snapshot(clock.instant(), null, now);
        final Map<AlertType, Alert> current = byType(now);
        final Map<AlertType, Alert> previous = last;
        last = current;
        if (previous == null) {
            return;
        }
        for (final AlertType type : AlertType.values()) {
            final Alert before = previous.get(type);
            final Alert after = current.get(type);
            if (!sameFact(before, after)) {
                raise(after != null ? after : cleared(Objects.requireNonNull(before)));
            }
        }
    }

    /** Raises every run that failed lately and was not raised yet; never throws. */
    public void runs() {
        final List<UpdateRequest> finished;
        try {
            finished = runs.finishedWithin(RUN_WINDOW);
        } catch (final RuntimeException failure) {
            log.warn("Could not read the finished runs for the alerts", failure);
            return;
        }
        for (final UpdateRequest run : finished) {
            if (run.status() != UpdateStatus.FAILED) {
                continue;
            }
            final Alert alert = new Alert(
                    AlertType.RUN,
                    Alert.Level.DOWN,
                    run.kind().name().toLowerCase(Locale.ROOT),
                    TEXTS.alert().runFailed(run.kind()),
                    List.of(TEXTS.alert().run(run.id())),
                    "/operations/updates/" + run.id());
            try {
                if (book.raiseOnce("run:" + run.id(), alert, RAISED_BY)) {
                    log.info("Run {} failed, so it was raised as an alert.", run.id());
                }
            } catch (final RuntimeException failure) {
                log.warn("Could not raise the failed run {}", run.id(), failure);
            }
        }
    }

    private void raise(final Alert alert) {
        try {
            book.raise(alert, RAISED_BY);
            log.info("{} is now {}: {}", alert.type().key(), alert.level().key(), alert.subject());
        } catch (final RuntimeException failure) {
            log.warn("Could not raise the alert of {} on {}", alert.type().key(), alert.subject(), failure);
        }
    }

    /** One alert per type: the worst one, naming every subject of its type. */
    static Map<AlertType, Alert> byType(final List<Alert> alerts) {
        final Map<AlertType, List<Alert>> grouped = new EnumMap<>(AlertType.class);
        for (final Alert alert : alerts) {
            grouped.computeIfAbsent(alert.type(), ignored -> new ArrayList<>()).add(alert);
        }
        final Map<AlertType, Alert> merged = new EnumMap<>(AlertType.class);
        grouped.forEach((type, all) -> merged.put(type, merge(all)));
        return merged;
    }

    private static Alert merge(final List<Alert> all) {
        final Alert worst = all.stream()
                .filter(alert -> alert.level() == Alert.Level.DOWN)
                .findFirst()
                .orElse(all.getFirst());
        if (all.size() == 1) {
            return worst;
        }
        final List<String> subjects =
                all.stream().map(Alert::subject).distinct().toList();
        return new Alert(
                worst.type(),
                worst.level(),
                String.join(", ", subjects),
                TEXTS.alert().several(subjects, all.size()),
                all.stream().map(Alert::title).toList(),
                worst.path());
    }

    /** Whether two readings of one type tell the same; the title is left out, since it carries numbers. */
    private static boolean sameFact(final @Nullable Alert before, final @Nullable Alert after) {
        if (before == null || after == null) {
            return before == null && after == null;
        }
        return before.level() == after.level() && before.subject().equals(after.subject());
    }

    private static Alert cleared(final Alert was) {
        return new Alert(
                was.type(), Alert.Level.OK, was.subject(), TEXTS.alert().clear(was.subject()), was.path());
    }
}
