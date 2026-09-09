package eu.nordtal.s2.common.notify;

import org.slf4j.Logger;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A thread parked on a dedicated {@code LISTEN} connection, re-reading whatever it is told to
 * whenever something arrives - and whenever it has just (re)connected.
 *
 * <p><b>Notifications are lost while a process is disconnected</b>, so every {@link Refresh} runs
 * immediately after a successful connect, before a single notification has been waited for. A
 * notification is an optimisation, never the state.
 *
 * <p><b>The caller's poll is the guarantee</b>; this only makes a change feel instant. Every process
 * that starts a listener also schedules the same refreshes on a timer and can turn this half off.
 *
 * <p>A refresh that throws is logged and retried on the next signal rather than taking the thread
 * down: several refreshes ride one connection. Which channel woke the loop is never inspected -
 * every refresh runs on every signal, because trusting a notification to say what changed is the one
 * thing this design does not do.
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
     * How long to wait before opening a new connection after one failed. Deliberately not
     * configuration: the poll runs regardless, so no behaviour depends on this number.
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

                // Re-read unconditionally, before waiting for anything: a change made while this
                // process was disconnected produced a notification nobody received, and no later
                // notification repeats it.
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
