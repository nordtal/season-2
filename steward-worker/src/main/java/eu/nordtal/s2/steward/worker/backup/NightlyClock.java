package eu.nordtal.s2.steward.worker.backup;

import eu.nordtal.s2.common.update.RunRefused;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateSource;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

/**
 * The nightly backup, asked for by the service that performs it.
 *
 * <h2>This is a rule being rewritten, not broken</h2>
 * {@code serve} had exactly one protection: it did nothing at all until a row appeared in
 * {@code update_request}, which is what stopped a crash restart at three in the morning from
 * moving a version. So the nightly row was written by {@code smp}, the one process that already
 * ran a daily clock. §9a changes that deliberately, and the reason is the hole it leaves: a season
 * with {@code smp} down has no nightly backup <b>and nothing says so</b>.
 *
 * <p>The protection is kept in the part that mattered. This clock writes a request row and nothing
 * else - it never claims one, never runs one, and never touches a jar. Everything downstream of the
 * row is the same path an admin's {@code /backup now} takes, lock and countdown included. What is
 * new is one row a night, from a timer, and that is the whole of the change.</p>
 *
 * <h2>Two lessons inherited from the clock it replaces</h2>
 * <b>The delay is rounded up, never down.</b> Its predecessor floored the wait, fired a fraction of
 * a second early, re-armed for another fraction, and wrote a row per pass - a backup submitting
 * itself in a tight loop, where the first one takes the lock and every one after it is refused into
 * the log.
 *
 * <p><b>It re-arms whether the write worked or not.</b> A database briefly unreachable at 04:45
 * must not cost every night after it as well, which is exactly what a schedule that only continues
 * on success does - silently, for the rest of the season.</p>
 */
public final class NightlyClock implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NightlyClock.class);

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** Who the row says asked. CONSOLE, because the database's CHECK allows three and this is
     * none of DISCORD or GAME - it is this host, on a timer, which is what CONSOLE has always
     * meant for the nightly row. */
    private static final UpdateSource SOURCE = UpdateSource.CONSOLE;

    private static final String REQUESTED_BY = "steward-worker (nightly)";

    private final UpdateDirectory directory;
    private final LocalTime at;
    private final Set<DayOfWeek> days;
    private final ZoneId zone;
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                final Thread thread = new Thread(runnable, "nightly-backup");
                thread.setDaemon(true);
                return thread;
            });

    private NightlyClock(final UpdateDirectory directory, final LocalTime at,
                         final Set<DayOfWeek> days, final ZoneId zone) {
        this.directory = directory;
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
    public static Optional<NightlyClock> from(final @NotNull UpdateDirectory directory,
                                              final String at, final @NotNull ZoneId zone) {
        return from(directory, at, null, zone);
    }

    /**
     * Reads {@code backup.at} and {@code backup.days}.
     *
     * @param days which weekdays it may run on; {@code null} is every night, which is what a
     *             config file written before this key existed says
     * @return empty when it is switched off, unreadable, or asked for no weekday at all
     */
    public static Optional<NightlyClock> from(final @NotNull UpdateDirectory directory,
                                              final String at, final List<String> days,
                                              final @NotNull ZoneId zone) {
        if (at == null || at.isBlank()) {
            return Optional.empty();
        }
        final LocalTime parsed = hour(at);
        if (parsed == null) {
            return Optional.empty();
        }
        final Set<DayOfWeek> weekdays = weekdays(days);
        if (weekdays.isEmpty()) {
            log.warn("backup.days lists no weekday this service can read, so there will be no"
                    + " nightly backup. Nothing else is affected.");
            return Optional.empty();
        }
        return Optional.of(new NightlyClock(directory, parsed, weekdays, zone));
    }

    /**
     * {@code backup.days} as a set, empty when the list is present and holds nothing usable.
     *
     * <p>Full names and the three-letter forms both, in any case and with any spacing around
     * them - this value is typed by an operator into a YAML file, and refusing {@code Mon} because
     * the enum spells it {@code MONDAY} is a config error nobody can see in a diff. A word that is
     * neither is logged and dropped rather than emptying the whole schedule, which is the same
     * decision {@code hour()} makes one field up.</p>
     */
    private static Set<DayOfWeek> weekdays(final List<String> days) {
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
                if (candidate.name().equals(word) || candidate.name().startsWith(word) && word.length() == 3) {
                    found = candidate;
                    break;
                }
            }
            if (found == null) {
                log.error("backup.days has \"{}\" in it, which is not a weekday. It is ignored;"
                        + " the rest of the list still schedules.", day);
                continue;
            }
            chosen.add(found);
        }
        return chosen;
    }

    /**
     * When the next nightly backup would be asked for, or empty when there is none.
     *
     * <h2>Why anybody outside this class asks</h2>
     * The interface offers "tonight" beside "now" for a run that stops servers, and tonight has to
     * land <em>before</em> this clock rather than on top of it: both take the same lock, so two runs
     * at the same minute are one run waiting for the other with the network already down. The
     * interface used to work that out in the browser's time zone, which is not this container's -
     * an admin one hour east of the host scheduled the thing it was avoiding.
     */
    public static Optional<ZonedDateTime> next(final String at, final @NotNull ZoneId zone,
                                               final @NotNull ZonedDateTime now) {
        return next(at, null, zone, now);
    }

    /**
     * The same answer, with {@code backup.days} taken into account.
     *
     * @param days {@code null} for every night - see {@link #from(UpdateDirectory, String, List, ZoneId)}
     */
    public static Optional<ZonedDateTime> next(final String at, final List<String> days,
                                               final @NotNull ZoneId zone,
                                               final @NotNull ZonedDateTime now) {
        final LocalTime parsed = hour(at);
        if (parsed == null) {
            return Optional.empty();
        }
        final Set<DayOfWeek> weekdays = weekdays(days);
        return weekdays.isEmpty() ? Optional.empty()
                : Optional.of(nextAt(parsed, weekdays, now.withZoneSameInstant(zone)));
    }

    /** {@code HH:mm}, or null for blank and for anything that is not a time - both are logged. */
    private static LocalTime hour(final String at) {
        if (at == null || at.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(at.strip(), HH_MM);
        } catch (DateTimeParseException e) {
            log.error("backup.at is \"{}\", which is not HH:mm. There will be no nightly backup"
                    + " until it is. Nothing else is affected.", at);
            return null;
        }
    }

    /**
     * Always strictly in the future, so asking exactly on the second cannot answer with now, and
     * always on one of {@code days} - up to seven days ahead, never one.
     *
     * <p>The loop is what makes a weekday schedule a weekday schedule: adding a single day when
     * today is not one of them and answering that is a daily backup with extra configuration.
     * {@code days} is non-empty by the time this is called, so it terminates within a week.</p>
     */
    private static ZonedDateTime nextAt(final LocalTime at, final Set<DayOfWeek> days,
                                        final ZonedDateTime now) {
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
        log.info("the nightly backup is asked for at {} {} on {} - next in {}h{}m",
                at, zone, days.size() == 7 ? "every day" : days, until.toHours(),
                until.toMinutesPart());
        arm(until);
    }

    private void arm(final Duration until) {
        arm(until, null);
    }

    private void arm(final Duration until, final ZonedDateTime due) {
        // Rounded UP. toSeconds() floors, and a wait of 04:44:59.6 floored to zero is a task that
        // fires while the target is still ahead and re-arms into a loop.
        final long seconds = Math.max(1, Math.ceilDiv(until.toNanos(), 1_000_000_000L));
        clock.schedule(() -> {
            final ZonedDateTime now = ZonedDateTime.now(zone);
            final ZonedDateTime tonight = due == null ? now : due;
            // Whatever happened inside. See the class comment.
            final Duration next = fire(tonight, now);
            arm(next, next.equals(RETRY) ? tonight : null);
        }, seconds, TimeUnit.SECONDS);
    }

    /**
     * How long to wait before asking again when another run is open: only one run happens in the
     * network at a time, and a backup refused for that reason is asked for again rather than lost.
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
    Duration fire(final @NotNull ZonedDateTime due, final @NotNull ZonedDateTime now) {
        try {
            final long id = directory.submit(UpdateKind.BACKUP, SOURCE, REQUESTED_BY,
                    Duration.ZERO).id();
            log.info("asked for the nightly backup as request {}", id);
        } catch (final RunRefused refused) {
            if (refused.reason() == RunRefused.Reason.RUN_OPEN
                    && now.plus(RETRY).isBefore(due.plus(PATIENCE))) {
                log.info("the nightly backup waits: {}. Asking again in {} minutes.",
                        refused.getMessage(), RETRY.toMinutes());
                return RETRY;
            }
            log.warn("the nightly backup was not asked for tonight - {}. Tomorrow is tried again.",
                    refused.getMessage());
        } catch (RuntimeException e) {
            log.warn("the nightly backup could not be asked for - nothing was saved tonight."
                    + " The clock carries on; tomorrow is tried again.", e);
        }
        return untilNext(now);
    }

    /** Always strictly in the future, so firing exactly on the second cannot re-arm at zero. */
    Duration untilNext(final @NotNull ZonedDateTime now) {
        return Duration.between(now, nextAt(at, days, now));
    }

    @Override
    public void close() {
        clock.shutdownNow();
    }
}
