package eu.nordtal.s2.steward.backup;

import eu.nordtal.s2.common.time.Scheduler;
import eu.nordtal.s2.database.update.UpdateDirectory;
import eu.nordtal.s2.steward.config.StewardSpec;
import java.time.Clock;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the nightly backup and the scheduled update clocks in step with the {@code steward} group.
 *
 * {@link #arm()} runs at start and after every save of the steward group, rebuilding both clocks.
 */
public final class Schedules implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Schedules.class);

    private final UpdateDirectory directory;
    private final StewardSpec config;
    private final Clock wall;
    private final Scheduler scheduler;
    private @Nullable NightlyClock backup;
    private @Nullable NightlyClock update;

    public Schedules(
            final UpdateDirectory directory, final StewardSpec config, final Clock wall, final Scheduler scheduler) {
        this.directory = directory;
        this.config = config;
        this.wall = wall;
        this.scheduler = scheduler;
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
                wall));
        if (backup == null) {
            log.info("backup.at is empty, so there is no nightly backup. Nothing else is affected.");
        }
        update = start(NightlyClock.from(
                directory,
                NightlyClock.Job.UPDATE,
                config.update().at(),
                config.update().days(),
                wall));
        if (update == null) {
            log.info("update.at is empty, so nothing updates on a schedule. An admin can still"
                    + " ask for an update at any time.");
        }
    }

    /** Returns whether each clock is running. */
    synchronized boolean[] running() {
        return new boolean[] {backup != null, update != null};
    }

    private @Nullable NightlyClock start(final Optional<NightlyClock> clock) {
        clock.ifPresent(armed -> armed.start(scheduler));
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
