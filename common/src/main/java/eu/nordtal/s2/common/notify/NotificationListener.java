package eu.nordtal.s2.common.notify;

import org.slf4j.Logger;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A thread parked on a dedicated {@code LISTEN} connection, re-reading whatever it is told to
 * whenever something arrives - and, more importantly, <b>whenever it has just (re)connected</b>.
 *
 * <h2>The one rule that matters</h2>
 * docs/season-phases.md: "<b>Notifications are lost while a process is disconnected.</b> Every
 * reconnect must re-read the row unconditionally - the notification is an optimisation, never the
 * state." That is why every {@link Refresh} runs immediately after a successful connect, before a
 * single notification has been waited for.
 *
 * <h2>This is not the guarantee</h2>
 * The caller's poll is. Everything here only makes a change feel instant. If it turns out to be more
 * trouble than it is worth, the fallback is to stop starting it and keep the poll; nothing else has
 * to change, which is exactly why nothing else depends on it. Every process that starts one also
 * schedules the same refreshes on a timer, and every process can turn this half off in its config.
 *
 * <h2>Every refresh is guarded, and the channel is never inspected</h2>
 * A refresh that throws logs and is retried on the next signal rather than taking the thread down
 * with it: several refreshes ride one connection, and one of them failing must not cost the others
 * their propagation. It also cannot lose anything, because the poll asks again regardless.
 *
 * <p>Which channel woke the loop is not looked at either. Every refresh runs on every signal. That
 * is one extra small query at moments that are rare by construction, and the alternative is trusting
 * a notification to say what changed - which is the one thing this design never does.</p>
 *
 * <h2>What a test can and cannot say about this class</h2>
 * The loop's shape - reconnect after a failure, re-read on every connect, keep going - is exercised
 * against a fake {@link Notifications}. That proves the control flow and <b>nothing about a real
 * dropped socket</b>: a fake that throws when asked to is not a network partition, a failed-over
 * database or a process that has been suspended for a minute. Closing the
 * docs/state-of-play.md#the-unverified-assumptions row needs a restart drill against a real
 * PostgreSQL with the connection killed underneath the process.
 */
public final class NotificationListener implements AutoCloseable {

    /**
     * One thing to re-read on every signal.
     *
     * @param what a name for the log line when it fails - "the season phase", "the admin roster"
     * @param task the re-read itself; must be safe to run repeatedly and from this thread
     */
    public record Refresh(String what, Runnable task) {

        public Refresh {
            Objects.requireNonNull(what, "what");
            Objects.requireNonNull(task, "task");
        }
    }

    /**
     * How long to wait before opening a new connection after one failed.
     * <p>
     * Deliberately not configuration. It is bounded above by something that already is: the caller's
     * poll runs regardless, so a listener that is slow to come back costs nothing but the "instant"
     * feeling, and a listener that reconnects in a tight loop against a database that is down costs
     * log noise and connection attempts. No document settles this number, and no document needs to,
     * because no behaviour depends on it.
     * </p>
     */
    private static final Duration RECONNECT_BACKOFF = Duration.ofSeconds(5);

    private final Notifications.Connector connector;
    private final String threadName;
    private final List<Refresh> refreshes;
    private final Logger logger;
    private final Duration waitTimeout;
    private final Duration reconnectBackoff;

    private final AtomicReference<Notifications> current = new AtomicReference<>();
    private volatile boolean running = true;
    private volatile Thread thread;

    /**
     * @param connector   how to open a {@code LISTEN} connection
     * @param threadName  what the daemon thread calls itself; it outlives every stack trace it
     *                    appears in, so it names the process and the job
     * @param refreshes   what to re-read on every connect and every notification, in order
     * @param logger      the process logger
     * @param waitTimeout how long one {@code getNotifications} wait blocks for. Pass the poll
     *                    interval: the Postgres implementation follows every timeout with a liveness
     *                    check, so a shorter wait buys nothing but extra round trips
     */
    public NotificationListener(final Notifications.Connector connector, final String threadName,
                                final List<Refresh> refreshes, final Logger logger,
                                final Duration waitTimeout) {
        this(connector, threadName, refreshes, logger, waitTimeout, RECONNECT_BACKOFF);
    }

    /** Package-visible so a test can watch several reconnects without waiting seconds for each. */
    NotificationListener(final Notifications.Connector connector, final String threadName,
                         final List<Refresh> refreshes, final Logger logger,
                         final Duration waitTimeout, final Duration reconnectBackoff) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.threadName = Objects.requireNonNull(threadName, "threadName");
        this.refreshes = List.copyOf(Objects.requireNonNull(refreshes, "refreshes"));
        this.logger = Objects.requireNonNull(logger, "logger");
        this.waitTimeout = Objects.requireNonNull(waitTimeout, "waitTimeout");
        this.reconnectBackoff = Objects.requireNonNull(reconnectBackoff, "reconnectBackoff");
        if (this.refreshes.isEmpty()) {
            throw new IllegalArgumentException(
                    "a listener with nothing to refresh would wake up and do nothing");
        }
    }

    /** Starts the listener on its own daemon thread. Calling this twice is a programming error. */
    public void start() {
        if (thread != null) {
            throw new IllegalStateException("This listener has already been started");
        }
        final Thread listenerThread = new Thread(this::run, threadName);
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
            try (Notifications notifications = connector.listen()) {
                current.set(notifications);
                logger.info("{} is listening", threadName);

                // THE rule: re-read unconditionally, before waiting for anything. A change that
                // happened while this process was disconnected produced a notification nobody
                // received, and no later notification will repeat it.
                refreshAll();

                while (running) {
                    if (notifications.awaitNotification(waitTimeout)) {
                        refreshAll();
                    }
                }
            } catch (final SQLException exception) {
                if (!running) {
                    break;
                }
                logger.warn("{} lost its connection; retrying in {}s. The {}s poll is unaffected and"
                                + " remains the actual guarantee.",
                        threadName, reconnectBackoff.toSeconds(), waitTimeout.toSeconds(), exception);
                if (!sleepBeforeRetry()) {
                    break;
                }
            } catch (final RuntimeException exception) {
                if (!running) {
                    break;
                }
                logger.error("{} failed unexpectedly; retrying in {}s",
                        threadName, reconnectBackoff.toSeconds(), exception);
                if (!sleepBeforeRetry()) {
                    break;
                }
            } finally {
                current.set(null);
            }
        }
        logger.info("{} has stopped", threadName);
    }

    private void refreshAll() {
        for (final Refresh refresh : refreshes) {
            try {
                refresh.task().run();
            } catch (final RuntimeException failure) {
                logger.warn("Could not refresh {}; the listener carries on and will try again on the"
                        + " next notification.", refresh.what(), failure);
            }
        }
    }

    /** @return {@code false} when the wait was interrupted, which means "stop" */
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

        final Notifications open = current.getAndSet(null);
        if (open != null) {
            open.close();
        }

        final Thread listenerThread = this.thread;
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
    }
}
