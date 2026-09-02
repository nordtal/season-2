package eu.nordtal.s2.networkcontrol.phase;

import eu.nordtal.s2.networkcontrol.config.DatabaseSpec;

import org.slf4j.Logger;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The {@code LISTEN nordtal_phase} half of the phase model: a thread parked on a dedicated
 * connection, re-reading the phase row whenever it is told something changed - and, more
 * importantly, <b>whenever it has just (re)connected</b>.
 *
 * <h2>The one rule that matters</h2>
 * docs/season-phases.md: "<b>Notifications are lost while a process is disconnected.</b> Every
 * reconnect must re-read the row unconditionally - the notification is an optimisation, never the
 * state." That is why {@link PhaseWatch#refresh()} is called immediately after every successful
 * connect in the loop below, before a single notification has been waited for. The payload is empty
 * on purpose as well, so there would be nothing to trust even if one had arrived.
 *
 * <h2>This is not the guarantee</h2>
 * The 30-second poll is. Everything here only makes a switch feel instant, and it is built in the
 * same pass as the poll rather than deferred because the dedicated connection, the
 * {@code getNotifications(timeout)} thread and the reconnect re-read are easier to get right while
 * the phase model is being written than to retrofit into it. If it turns out to be more trouble
 * than it is worth, the row in docs/state-of-play.md#the-unverified-assumptions fallback is to delete it and keep the
 * poll; nothing else has to change, which is exactly why nothing else depends on it.
 *
 * <h2>What a test can and cannot say about this class</h2>
 * The loop's shape - reconnect after a failure, re-read on every connect, keep going - is exercised
 * against a fake {@link PhaseNotifications}. That proves the control flow and <b>nothing about a
 * real dropped socket</b>: a fake that throws when asked to is not a network partition, a
 * failed-over database or a proxy that has been suspended for a minute. Closing
 * the row in docs/state-of-play.md#the-unverified-assumptions row needs a restart drill against a real PostgreSQL with
 * the connection killed underneath the proxy.
 */
public final class PhaseListener implements AutoCloseable {

    /**
     * How long to wait before opening a new connection after one failed.
     * <p>
     * Deliberately not configuration. It is bounded above by something that already is: the poll
     * runs every {@code phase-poll-interval-seconds} regardless, so a listener that is slow to come
     * back costs nothing but the "instant" feeling, and a listener that reconnects in a tight loop
     * against a database that is down costs log noise and connection attempts. No document settles
     * this number, and no document needs to, because no behaviour depends on it.
     * </p>
     */
    private static final Duration RECONNECT_BACKOFF = Duration.ofSeconds(5);

    private final PhaseNotifications.Connector connector;
    private final PhaseWatch watch;

    /**
     * Re-reads the admin flags of everybody connected. Runs on exactly the same signals as
     * {@link PhaseWatch#refresh()} - every connect and every notification - because both answer the
     * same question ("what does the database say now?") and neither may trust a notification as
     * state. See {@code PostgresPhaseNotifications#ADMIN_CHANNEL} for why one connection carries
     * both channels instead of there being two of everything.
     */
    private final Runnable admins;
    private final Logger logger;
    private final Duration waitTimeout;
    private final Duration reconnectBackoff;

    private final AtomicReference<PhaseNotifications> current = new AtomicReference<>();
    private volatile boolean running = true;
    private volatile Thread thread;

    /**
     * @param connector   how to open a {@code LISTEN} connection
     * @param watch       what to refresh; every connect and every notification calls
     *                    {@link PhaseWatch#refresh()}
     * @param logger      the plugin logger
     * @param waitTimeout how long one {@code getNotifications} wait blocks for. Pass the poll
     *                    interval: the Postgres implementation follows every timeout with a
     *                    liveness check, so a shorter wait buys nothing but extra round trips
     */
    public PhaseListener(final PhaseNotifications.Connector connector, final PhaseWatch watch,
                         final Runnable admins, final Logger logger, final Duration waitTimeout) {
        this(connector, watch, admins, logger, waitTimeout, RECONNECT_BACKOFF);
    }

    /** Package-visible so a test can watch several reconnects without waiting seconds for each. */
    PhaseListener(final PhaseNotifications.Connector connector, final PhaseWatch watch,
                  final Runnable admins, final Logger logger, final Duration waitTimeout,
                  final Duration reconnectBackoff) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.watch = Objects.requireNonNull(watch, "watch");
        this.admins = Objects.requireNonNull(admins, "admins");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.waitTimeout = Objects.requireNonNull(waitTimeout, "waitTimeout");
        this.reconnectBackoff = Objects.requireNonNull(reconnectBackoff, "reconnectBackoff");
    }

    /**
     * @param config the database credentials; the listener connects to the same database the pool
     *               does, just not through it
     * @return a connector over a dedicated pgjdbc connection
     */
    public static PhaseNotifications.Connector postgres(final DatabaseSpec config) {
        return PostgresPhaseNotifications.connector(config);
    }

    /** Starts the listener on its own daemon thread. Calling this twice is a programming error. */
    public void start() {
        if (thread != null) {
            throw new IllegalStateException("This phase listener has already been started");
        }
        final Thread listenerThread = new Thread(this::run, "network-control-phase-listener");
        listenerThread.setDaemon(true);
        this.thread = listenerThread;
        listenerThread.start();
    }

    /**
     * The connect / re-read / wait loop. Package-visible rather than private so a test can drive it
     * on a thread of its own choosing.
     */
    void run() {
        while (running) {
            try (PhaseNotifications notifications = connector.listen()) {
                current.set(notifications);
                logger.info("Listening for season phase changes on {} and admin changes on {}",
                        PostgresPhaseNotifications.CHANNEL, PostgresPhaseNotifications.ADMIN_CHANNEL);

                // THE rule: re-read unconditionally, before waiting for anything. A switch that
                // happened while this process was disconnected produced a notification nobody
                // received, and no later notification will repeat it. It holds identically for the
                // admin flags, which is why both are refreshed here and not only the phase.
                watch.refresh();
                refreshAdmins();

                while (running) {
                    if (notifications.awaitNotification(waitTimeout)) {
                        // Which channel it arrived on is not inspected, deliberately. Both refreshes
                        // are one small idempotent query, and a listener that routed by channel
                        // would be trusting the notification to tell it what changed - which is the
                        // one thing this design never does.
                        watch.refresh();
                        refreshAdmins();
                    }
                }
            } catch (final SQLException exception) {
                if (!running) {
                    break;
                }
                logger.warn("The season phase listener lost its connection; retrying in {}s. The "
                                + "{}s poll is unaffected and remains the actual guarantee.",
                        reconnectBackoff.toSeconds(), waitTimeout.toSeconds(), exception);
                if (!sleepBeforeRetry()) {
                    break;
                }
            } catch (final RuntimeException exception) {
                if (!running) {
                    break;
                }
                logger.error("The season phase listener failed unexpectedly; retrying in {}s",
                        reconnectBackoff.toSeconds(), exception);
                if (!sleepBeforeRetry()) {
                    break;
                }
            } finally {
                current.set(null);
            }
        }
        logger.info("The season phase listener has stopped");
    }

    /** @return {@code false} when the wait was interrupted, which means "stop" */
    /**
     * Never lets a failed admin refresh take the listener down.
     * <p>
     * The phase is the reason this thread exists; the admin roster riding along must not be able to
     * cost the network its phase propagation. A failure here is also self-correcting - the next
     * notification or reconnect asks again.
     * </p>
     */
    private void refreshAdmins() {
        try {
            admins.run();
        } catch (final RuntimeException failure) {
            logger.warn("Could not refresh the admin roster; the phase listener carries on and will"
                    + " try again on the next notification.", failure);
        }
    }

    private boolean sleepBeforeRetry() {
        try {
            Thread.sleep(reconnectBackoff);
            return running;
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Stops the loop and closes the connection out from under the blocking wait, which is what
     * makes a shutdown immediate rather than one {@code waitTimeout} long.
     */
    @Override
    public void close() {
        running = false;

        final PhaseNotifications open = current.getAndSet(null);
        if (open != null) {
            open.close();
        }

        final Thread listenerThread = this.thread;
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
    }
}
