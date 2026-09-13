package eu.nordtal.s2.steward.worker.backup;

import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateKind;
import eu.nordtal.s2.common.update.UpdateSource;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
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
    private final ZoneId zone;
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                final Thread thread = new Thread(runnable, "nightly-backup");
                thread.setDaemon(true);
                return thread;
            });

    private NightlyClock(final UpdateDirectory directory, final LocalTime at, final ZoneId zone) {
        this.directory = directory;
        this.at = at;
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
        if (at == null || at.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new NightlyClock(directory, LocalTime.parse(at.strip(), HH_MM), zone));
        } catch (DateTimeParseException e) {
            log.error("backup.at is \"{}\", which is not HH:mm. There will be no nightly backup"
                    + " until it is. Nothing else is affected.", at);
            return Optional.empty();
        }
    }

    public void start() {
        final Duration until = untilNext(ZonedDateTime.now(zone));
        log.info("the nightly backup is asked for at {} {} - next in {}h{}m",
                at, zone, until.toHours(), until.toMinutesPart());
        arm(until);
    }

    private void arm(final Duration until) {
        // Rounded UP. toSeconds() floors, and a wait of 04:44:59.6 floored to zero is a task that
        // fires while the target is still ahead and re-arms into a loop.
        final long seconds = Math.max(1, Math.ceilDiv(until.toNanos(), 1_000_000_000L));
        clock.schedule(this::fire, seconds, TimeUnit.SECONDS);
    }

    private void fire() {
        try {
            final long id = directory.submit(UpdateKind.BACKUP, SOURCE, REQUESTED_BY,
                    Duration.ZERO).id();
            log.info("asked for the nightly backup as request {}", id);
        } catch (RuntimeException e) {
            log.warn("the nightly backup could not be asked for - nothing was saved tonight."
                    + " The clock carries on; tomorrow is tried again.", e);
        } finally {
            // Whatever happened above. See the class comment.
            arm(untilNext(ZonedDateTime.now(zone)));
        }
    }

    /** Always strictly in the future, so firing exactly on the second cannot re-arm at zero. */
    Duration untilNext(final @NotNull ZonedDateTime now) {
        ZonedDateTime next = now.with(at);
        if (!next.isAfter(now)) {
            next = next.plusDays(1).with(at);
        }
        return Duration.between(now, next);
    }

    @Override
    public void close() {
        clock.shutdownNow();
    }
}
