package eu.nordtal.season.steward.backup;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.time.Scheduler;
import eu.nordtal.season.database.DatabaseText;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.database.update.UpdateRefusal;
import eu.nordtal.season.messages.Refused;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Writes a request row for the nightly backup or the scheduled update. */
public final class NightlyClock implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NightlyClock.class);

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** The request a clock writes, as {@link Actor#STEWARD}. */
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
    private @Nullable Scheduler scheduler;
    private Scheduler.@Nullable Task armed;
    private boolean closed;

    private NightlyClock(
            final UpdateDirectory directory,
            final Job job,
            final LocalTime at,
            final Set<DayOfWeek> days,
            final Clock wall) {
        this.directory = directory;
        this.job = job;
        this.at = at;
        this.days = days;
        this.wall = wall;
        this.zone = wall.getZone();
    }

    /**
     * Reads {@code backup.at}.
     *
     * @param at {@code HH:mm} in the network's default zone, or blank for no nightly backup
     * @return empty when off or unreadable
     */
    public static Optional<NightlyClock> from(
            final UpdateDirectory directory, final @Nullable String at, final Clock wall) {
        return from(directory, at, null, wall);
    }

    /**
     * Reads {@code backup.at} and {@code backup.days}.
     *
     * @param days the weekdays it runs on, or {@code null} for every night
     * @return empty when off, unreadable or without a weekday
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
     * @return empty when off, unreadable or without a weekday
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

    /** Returns {@code <job>.days} as a set. It matches full and three-letter names in any case and drops others. */
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

    /** Returns the next time the nightly backup is asked for in {@code zone}, or empty. */
    public static Optional<ZonedDateTime> next(final @Nullable String at, final ZoneId zone, final ZonedDateTime now) {
        return next(at, null, zone, now);
    }

    /**
     * Returns the next time the nightly backup is asked for on one of {@code days}.
     *
     * @param days the weekdays, or {@code null} for every night
     */
    public static Optional<ZonedDateTime> next(
            final @Nullable String at, final @Nullable List<String> days, final ZoneId zone, final ZonedDateTime now) {
        return next(Job.BACKUP, at, days, zone, now);
    }

    /** Returns the next time {@code job} is asked for on one of {@code days}. */
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

    /** Returns the parsed {@code HH:mm}, or {@code null} for a blank or invalid value. */
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

    /** Returns the next moment on one of the non-empty {@code days}, after {@code now} and within seven days. */
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

    /** Schedules the clock on {@code on} and again after every firing until it is closed. */
    public synchronized void start(final Scheduler on) {
        this.scheduler = on;
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

    private synchronized void arm(final Duration until, final @Nullable ZonedDateTime due) {
        if (closed) {
            return;
        }
        // Rounds up, so the wait is never zero.
        final long seconds = Math.max(1, Math.ceilDiv(until.toNanos(), 1_000_000_000L));
        armed = Objects.requireNonNull(scheduler, "armed only after start").after(Duration.ofSeconds(seconds), () -> {
            final ZonedDateTime now = ZonedDateTime.now(wall);
            final ZonedDateTime tonight = due == null ? now : due;
            final Duration next = fire(tonight, now);
            arm(next, next.equals(RETRY) ? tonight : null);
        });
    }

    /** The wait before the next try while another run is open. */
    static final Duration RETRY = Duration.ofMinutes(5);

    /** The time after its moment during which tonight's request is still tried. */
    static final Duration PATIENCE = Duration.ofHours(2);

    /**
     * Writes the request row once.
     *
     * @param due the time the request was first due
     * @return {@link #RETRY} while another run is open within {@link #PATIENCE}, else the wait until the next day
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

    /** Returns the wait until the next moment, which is after {@code now}. */
    Duration untilNext(final ZonedDateTime now) {
        return Duration.between(now, nextAt(at, days, now));
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (armed != null) {
            armed.cancel();
        }
    }
}
