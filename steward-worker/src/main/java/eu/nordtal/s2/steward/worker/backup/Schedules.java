package eu.nordtal.s2.steward.worker.backup;

import eu.nordtal.s2.database.update.UpdateDirectory;
import eu.nordtal.s2.steward.worker.config.StewardSpec;
import java.time.ZoneId;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the nightly backup and the scheduled update clocks in step with {@code steward.yml}.
 *
 * {@link #arm()} runs at start and after every save of this worker's config, rebuilding both clocks.
 */
public final class Schedules implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Schedules.class);

    private final UpdateDirectory directory;
    private final StewardSpec config;
    private final ZoneId zone;
    private @Nullable NightlyClock backup;
    private @Nullable NightlyClock update;

    public Schedules(final UpdateDirectory directory, final StewardSpec config, final ZoneId zone) {
        this.directory = directory;
        this.config = config;
        this.zone = zone;
        this.backup = null;
        this.update = null;
    }

    /** Closes both clocks and starts them again from what the config now says. */
    public synchronized void arm() {
        close();
        backup = start(NightlyClock.from(
                directory,
                NightlyClock.Job.BACKUP,
                config.backup().at(),
                config.backup().days(),
                zone));
        if (backup == null) {
            log.info("backup.at is empty, so there is no nightly backup. Nothing else is affected.");
        }
        update = start(NightlyClock.from(
                directory,
                NightlyClock.Job.UPDATE,
                config.update().at(),
                config.update().days(),
                zone));
        if (update == null) {
            log.info("update.at is empty, so nothing updates on a schedule. An admin can still"
                    + " ask for an update at any time.");
        }
    }

    /** Returns whether each clock is running. */
    synchronized boolean[] running() {
        return new boolean[] {backup != null, update != null};
    }

    private static @Nullable NightlyClock start(final Optional<NightlyClock> clock) {
        clock.ifPresent(NightlyClock::start);
        return clock.orElse(null);
    }

    @Override
    public synchronized void close() {
        if (backup != null) {
            backup.close();
            backup = null;
        }
        if (update != null) {
            update.close();
            update = null;
        }
    }
}
