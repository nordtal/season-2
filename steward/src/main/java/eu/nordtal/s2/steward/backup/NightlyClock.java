package eu.nordtal.s2.steward.backup;

import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.DatabaseText;
import eu.nordtal.s2.database.update.UpdateDirectory;
import eu.nordtal.s2.database.update.UpdateKind;
import eu.nordtal.s2.database.update.UpdateRefusal;
import eu.nordtal.s2.messages.Refused;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks for the nightly backup by writing a request row, and nothing else.
 *
 * The delay rounds up so it never fires early, and it re-arms whether or not the write worked.
 */
public final class NightlyClock implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NightlyClock.class);

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** What a clock asks for, always as {@link Actor#STEWARD}: nobody pressed anything. */
    public enum Job {
        BACKUP(UpdateKind.BACKUP, "backup", "the nightly backup"),
        UPDATE(UpdateKind.UPDATE, "update", "the scheduled update");

        private final UpdateKind kind;
        private final String key;
        private final String noun;

        Job(final UpdateKind kind, final String key, final String noun) {
            this.kind = kind;
            this.key = key;
            this.noun = noun;
        }
    }

    private final UpdateDirectory directory;
    private final Job job;
    private final LocalTime at;
    private final Set<DayOfWeek> days;
    private final ZoneId zone;
    private final Clock wall;
    private final ScheduledExecutorService clock;

    private NightlyClock(
            final UpdateDirectory directory,
            final Job job,
            final LocalTime at,
            final Set<DayOfWeek> days,
            final Clock wall) {
        this.directory = directory;
        this.job = job;
        this.clock = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "clock-" + job.key);
            thread.setDaemon(true);
            return thread;
        });
        this.at = at;
        this.days = days;
        this.wall = wall;
        this.zone = wall.getZone();
    }

    /**
     * Reads {@code backup.at}.
     *
     * @param at {@code HH:mm} in the network's default zone, or blank for no nightly backup
     * @return empty when switched off or unreadable, and unreadable is logged
     */
    public static Optional<NightlyClock> from(
            final UpdateDirectory directory, final @Nullable String at, final Clock wall) {
        return from(directory, at, null, wall);
    }

    /**
     * Reads {@code backup.at} and {@code backup.days}.
     *
     * @param days which weekdays it may run on; {@code null} is every night
     * @return empty when switched off, unreadable, or given no weekday
     */
    public static Optional<NightlyClock> from(
            final UpdateDirectory directory,
            final @Nullable String at,
            final @Nullable List<String> days,
            final Clock wall) {
        return from(directory, Job.BACKUP, at, days, wall);
    }

    /**
     * Reads {@code <job>.at} and {@code <job>.days}.
     *
     * @return empty when switched off, unreadable, or given no weekday
     */
    public static Optional<NightlyClock> from(
            final UpdateDirectory directory,
            final Job job,
            final @Nullable String at,
            final @Nullable List<String> days,
            final Clock wall) {
        if (at == null || at.isBlank()) {
            return Optional.empty();
        }
        final LocalTime parsed = hour(job, at);
        if (parsed == null) {
            return Optional.empty();
        }
        final Set<DayOfWeek> weekdays = weekdays(job, days);
        if (weekdays.isEmpty()) {
            log.warn(
                    "{}.days lists no weekday this service can read, so {} never runs." + " Nothing else is affected.",
                    job.key,
                    job.noun);
            return Optional.empty();
        }
        return Optional.of(new NightlyClock(directory, job, parsed, weekdays, wall));
    }

    /**
     * Returns {@code <job>.days} as a set, empty when the list holds nothing usable.
     *
     * Full and three-letter names match in any case; an unknown word is logged and dropped.
     */
    private static Set<DayOfWeek> weekdays(final Job job, final @Nullable List<String> days) {
        if (days == null) {
            return EnumSet.allOf(DayOfWeek.class);
        }
        final Set<DayOfWeek> chosen = EnumSet.noneOf(DayOfWeek.class);
        for (final String day : days) {
            if (day == null || day.isBlank()) {
                continue;
            }
            final String word = day.strip().toUpperCase(java.util.Locale.ROOT);
            DayOfWeek found = null;
            for (final DayOfWeek candidate : DayOfWeek.values()) {
                if (candidate.name().equals(word) || (candidate.name().startsWith(word) && word.length() == 3)) {
                    found = candidate;
                    break;
                }
            }
            if (found == null) {
                log.error(
                        "{}.days has \"{}\" in it, which is not a weekday. It is ignored;"
                                + " the rest of the list still schedules.",
                        job.key,
                        day);
                continue;
            }
            chosen.add(found);
        }
        return chosen;
    }

    /**
     * Returns when the next nightly backup would be asked for, in the network's default zone, or empty.
     *
     * The interface uses it so a "tonight" run lands before this clock rather than on top of it.
     */
    public static Optional<ZonedDateTime> next(final @Nullable String at, final ZoneId zone, final ZonedDateTime now) {
        return next(at, null, zone, now);
    }

    /**
     * Returns the same answer with {@code backup.days} taken into account.
     *
     * @param days {@code null} for every night
     */
    public static Optional<ZonedDateTime> next(
            final @Nullable String at, final @Nullable List<String> days, final ZoneId zone, final ZonedDateTime now) {
        return next(Job.BACKUP, at, days, zone, now);
    }

    /** Returns the same answer for either clock; {@code job} only decides which key a log line names. */
    public static Optional<ZonedDateTime> next(
            final Job job,
            final @Nullable String at,
            final @Nullable List<String> days,
            final ZoneId zone,
            final ZonedDateTime now) {
        final LocalTime parsed = hour(job, at);
        if (parsed == null) {
            return Optional.empty();
        }
        final Set<DayOfWeek> weekdays = weekdays(job, days);
        return weekdays.isEmpty()
                ? Optional.empty()
                : Optional.of(nextAt(parsed, weekdays, now.withZoneSameInstant(zone)));
    }

    /** Returns {@code HH:mm}, or null for blank and for anything that is not a time; both are logged. */
    private static @Nullable LocalTime hour(final Job job, final @Nullable String at) {
        if (at == null || at.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(at.strip(), HH_MM);
        } catch (DateTimeParseException e) {
            log.error(
                    "{}.at is \"{}\", which is not HH:mm. {} does not run until it is." + " Nothing else is affected.",
                    job.key,
                    at,
                    job.noun);
            return null;
        }
    }

    /**
     * Returns the next moment on one of {@code days}, strictly in the future and at most seven days ahead.
     *
     * {@code days} is non-empty by the time this is called, so the loop ends within a week.
     */
    private static ZonedDateTime nextAt(final LocalTime at, final Set<DayOfWeek> days, final ZonedDateTime now) {
        ZonedDateTime next = now.with(at);
        if (!next.isAfter(now)) {
            next = next.plusDays(1).with(at);
        }
        for (int ahead = 0; ahead < 7 && !days.contains(next.getDayOfWeek()); ahead++) {
            next = next.plusDays(1).with(at);
        }
        return next;
    }

    public void start() {
        final Duration until = untilNext(ZonedDateTime.now(wall));
        log.info(
                "{} is asked for at {} {} on {} - next in {}h{}m",
                job.noun,
                at,
                zone,
                days.size() == 7 ? "every day" : days,
                until.toHours(),
                until.toMinutesPart());
        arm(until);
    }

    private void arm(final Duration until) {
        arm(until, null);
    }

    private void arm(final Duration until, final @Nullable ZonedDateTime due) {
        // Rounded up: toSeconds() floors, and a wait floored to zero fires early and re-arms into a loop.
        final long seconds = Math.max(1, Math.ceilDiv(until.toNanos(), 1_000_000_000L));
        final var _ = clock.schedule(
                () -> {
                    final ZonedDateTime now = ZonedDateTime.now(wall);
                    final ZonedDateTime tonight = due == null ? now : due;
                    // Re-arms whether the request succeeded or not.
                    final Duration next = fire(tonight, now);
                    arm(next, next.equals(RETRY) ? tonight : null);
                },
                seconds,
                TimeUnit.SECONDS);
    }

    /** How long to wait before asking again while another run is open. */
    static final Duration RETRY = Duration.ofMinutes(5);

    /** How long after its moment tonight's backup is still asked for; after that, tomorrow. */
    static final Duration PATIENCE = Duration.ofHours(2);

    /**
     * Asks for tonight's backup once.
     *
     * @param due when tonight's backup was first due
     * @return {@link #RETRY} while another run is open and patience lasts, otherwise the wait until the next night
     */
    Duration fire(final ZonedDateTime due, final ZonedDateTime now) {
        try {
            final long id =
                    directory.submit(job.kind, Actor.STEWARD, Duration.ZERO).id();
            log.info("asked for {} as request {}", job.noun, id);
        } catch (final Refused refused) {
            if (refused.reason() == UpdateRefusal.RUN_OPEN && now.plus(RETRY).isBefore(due.plus(PATIENCE))) {
                log.info(
                        "{} waits: {} Asking again in {} minutes.",
                        job.noun,
                        DatabaseText.english(refused.refusal().message()),
                        RETRY.toMinutes());
                return RETRY;
            }
            log.warn(
                    "{} was not asked for this time: {} The next scheduled day is tried again.",
                    job.noun,
                    DatabaseText.english(refused.refusal().message()));
        } catch (RuntimeException e) {
            log.warn(
                    "{} could not be asked for this time. The clock carries on; the next"
                            + " scheduled day is tried again.",
                    job.noun,
                    e);
        }
        return untilNext(now);
    }

    /** Always strictly in the future, so firing exactly on the second cannot re-arm at zero. */
    Duration untilNext(final ZonedDateTime now) {
        return Duration.between(now, nextAt(at, days, now));
    }

    @Override
    public void close() {
        clock.shutdownNow();
    }
}
