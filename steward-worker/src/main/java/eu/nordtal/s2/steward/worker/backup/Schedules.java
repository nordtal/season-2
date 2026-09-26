package eu.nordtal.s2.steward.worker.backup;

import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.steward.worker.config.StewardSpec;
import java.time.ZoneId;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The two clocks - the nightly backup and the optional scheduled update - kept in step with
 * {@code steward.yml}.
 *
 * <p>Both used to be read once, at start, so a schedule saved in Steward only took effect after the
 * container restarted, while the page already showed the new one. {@link #arm()} is called at start
 * and again after every save of this worker's own config: it closes whatever is running and builds
 * both clocks again from the values as they now stand. {@code config} is the live instance of a
 * {@code ConfigHandle}, so a reload of that handle is all it takes for the values to be new.</p>
 */
public final class Schedules implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Schedules.class);

    private final UpdateDirectory directory;
    private final StewardSpec config;
    private final ZoneId zone;
    private NightlyClock backup;
    private NightlyClock update;

    public Schedules(final UpdateDirectory directory, final StewardSpec config, final ZoneId zone) {
        this.directory = directory;
        this.config = config;
        this.zone = zone;
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

    /** Whether each clock is running - for the test, which cannot wait until 04:45. */
    synchronized boolean[] running() {
        return new boolean[] {backup != null, update != null};
    }

    private static NightlyClock start(final Optional<NightlyClock> clock) {
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
