package eu.nordtal.s2.steward.worker.backup;

import eu.nordtal.s2.common.update.RunRefused;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateSource;
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
 * The nightly backup, asked for by the service that performs it.
 *
 * This clock writes a request row and nothing else - it never claims one, never runs one, and never touches a jar.
 * Everything downstream of the row is the same path an admin's {@code /backup now} takes, lock and countdown
 * included. A season where the process that would otherwise write this row is down still gets a nightly backup.
 *
 * The delay is rounded up, never down: flooring it risks firing a fraction of a second early and re-arming for
 * another fraction, writing a row per pass in a tight loop where the first one takes the lock and every one after
 * it is refused into the log.
 *
 * It re-arms whether the write worked or not. A database briefly unreachable at 04:45 must not cost every night
 * after it as well, which is what a schedule that only continues on success would do, silently, for the rest of
 * the season.
 */
public final class NightlyClock implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NightlyClock.class);

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Who the row says asked.
     *
     * CONSOLE, because the database's CHECK allows three and this is none of DISCORD or GAME - it is this host, on
     * a timer, which is what CONSOLE has always meant for the nightly row.
     */
    private static final UpdateSource SOURCE = UpdateSource.CONSOLE;

    /**
     * What a clock asks for.
     *
     * The backup was the only one until the update schedule joined it; the two differ in the row they write and the
     * words they log, and in nothing else.
     *
     * Both {@code requestedBy} values begin with {@code steward-worker}, which is what the interface reads as "the
     * clock, not a person".
     */
    public enum Job {
        BACKUP(UpdateKind.BACKUP, "backup", "steward-worker (nightly)", "the nightly backup"),
        UPDATE(UpdateKind.UPDATE, "update", "steward-worker (schedule)", "the scheduled update");

        private final UpdateKind kind;
        private final String key;
        private final String requestedBy;
        private final String noun;

        Job(final UpdateKind kind, final String key, final String requestedBy, final String noun) {
            this.kind = kind;
            this.key = key;
            this.requestedBy = requestedBy;
            this.noun = noun;
        }
    }

    private final UpdateDirectory directory;
    private final Job job;
    private final LocalTime at;
    private final Set<DayOfWeek> days;
    private final ZoneId zone;
    private final ScheduledExecutorService clock;

    private NightlyClock(
            final UpdateDirectory directory,
            final Job job,
            final LocalTime at,
            final Set<DayOfWeek> days,
            final ZoneId zone) {
        this.directory = directory;
        this.job = job;
        this.clock = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "clock-" + job.key);
            thread.setDaemon(true);
            return thread;
        });
        this.at = at;
        this.days = days;
        this.zone = zone;
    }

    /**
     * Reads {@code backup.at}.
     *
     * @param at   {@code HH:mm} in this container's own timezone, or blank for no nightly backup
     * @return empty when it is switched off or unreadable - and unreadable is logged as the
     *         configuration error it is, rather than silently becoming midnight
     */
    public static Optional<NightlyClock> from(
            final UpdateDirectory directory, final @Nullable String at, final ZoneId zone) {
        return from(directory, at, null, zone);
    }

    /**
     * Reads {@code backup.at} and {@code backup.days}.
     *
     * @param days which weekdays it may run on; {@code null} is every night, which is what a
     *             config file written before this key existed says
     * @return empty when it is switched off, unreadable, or asked for no weekday at all
     */
    public static Optional<NightlyClock> from(
            final UpdateDirectory directory,
            final @Nullable String at,
            final @Nullable List<String> days,
            final ZoneId zone) {
        return from(directory, Job.BACKUP, at, days, zone);
    }

    /**
     * Reads {@code <job>.at} and {@code <job>.days} - {@code backup.*} or {@code update.*}.
     *
     * @return empty when it is switched off, unreadable, or asked for no weekday at all
     */
    public static Optional<NightlyClock> from(
            final UpdateDirectory directory,
            final Job job,
            final @Nullable String at,
            final @Nullable List<String> days,
            final ZoneId zone) {
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
        return Optional.of(new NightlyClock(directory, job, parsed, weekdays, zone));
    }

    /**
     * {@code backup.days} as a set, empty when the list is present and holds nothing usable.
     *
     * Full names and the three-letter forms both, in any case and with any spacing around them: this value is typed
     * by an operator into a YAML file, and refusing {@code Mon} because the enum spells it {@code MONDAY} is a
     * config error nobody can see in a diff. A word that is neither is logged and dropped rather than emptying the
     * whole schedule, which is the same decision {@code hour()} makes one field up.
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
     * When the next nightly backup would be asked for, or empty when there is none.
     *
     * The interface offers "tonight" beside "now" for a run that stops servers, and tonight has to land before this
     * clock rather than on top of it: both take the same lock, so two runs at the same minute are one run waiting
     * for the other with the network already down. This answers in this container's own time zone, never the
     * browser's.
     */
    public static Optional<ZonedDateTime> next(final @Nullable String at, final ZoneId zone, final ZonedDateTime now) {
        return next(at, null, zone, now);
    }

    /**
     * The same answer, with {@code backup.days} taken into account.
     *
     * @param days {@code null} for every night - see {@link #from(UpdateDirectory, String, List, ZoneId)}
     */
    public static Optional<ZonedDateTime> next(
            final @Nullable String at, final @Nullable List<String> days, final ZoneId zone, final ZonedDateTime now) {
        return next(Job.BACKUP, at, days, zone, now);
    }

    /** The same answer for either clock - {@code job} only decides which key a log line names. */
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

    /** {@code HH:mm}, or null for blank and for anything that is not a time - both are logged. */
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
     * Always strictly in the future, and always on one of {@code days} - up to seven days ahead, never one.
     *
     * The loop is what makes a weekday schedule a weekday schedule: adding a single day when today is not one of
     * them and answering that is a daily backup with extra configuration. {@code days} is non-empty by the time
     * this is called, so it terminates within a week.
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
        final Duration until = untilNext(ZonedDateTime.now(zone));
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
                    final ZonedDateTime now = ZonedDateTime.now(zone);
                    final ZonedDateTime tonight = due == null ? now : due;
                    // Re-arms whether the request succeeded or not; see the class comment.
                    final Duration next = fire(tonight, now);
                    arm(next, next.equals(RETRY) ? tonight : null);
                },
                seconds,
                TimeUnit.SECONDS);
    }

    /**
     * How long to wait before asking again when another run is open.
     *
     * Only one run happens in the network at a time, and a backup refused for that reason is asked for again
     * rather than lost.
     */
    static final Duration RETRY = Duration.ofMinutes(5);

    /** How long after its moment tonight's backup is still asked for. After that, tomorrow. */
    static final Duration PATIENCE = Duration.ofHours(2);

    /**
     * Asks for tonight's backup once.
     *
     * @param due when tonight's backup was first due
     * @return the wait before this clock fires again: {@link #RETRY} while another run is open and
     *         tonight's patience lasts, otherwise until the next scheduled night
     */
    Duration fire(final ZonedDateTime due, final ZonedDateTime now) {
        try {
            final long id = directory
                    .submit(job.kind, SOURCE, job.requestedBy, Duration.ZERO)
                    .id();
            log.info("asked for {} as request {}", job.noun, id);
        } catch (final RunRefused refused) {
            if (refused.reason() == RunRefused.Reason.RUN_OPEN
                    && now.plus(RETRY).isBefore(due.plus(PATIENCE))) {
                log.info(
                        "{} waits: {}. Asking again in {} minutes.", job.noun, refused.getMessage(), RETRY.toMinutes());
                return RETRY;
            }
            log.warn(
                    "{} was not asked for this time - {}. The next scheduled day is tried again.",
                    job.noun,
                    refused.getMessage());
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
