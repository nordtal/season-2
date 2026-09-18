package eu.nordtal.s2.steward.worker.bunq;

import eu.nordtal.s2.common.notify.Channels;
import eu.nordtal.s2.common.notify.NotificationListener;
import eu.nordtal.s2.common.notify.Notifications;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * What drives {@link Payments}: a poll, and a {@code LISTEN nordtal_payment} that makes it feel
 * immediate.
 *
 * <h2>The poll is the guarantee</h2>
 * The same rule as everywhere else in this network. A notification is lost while a process is
 * disconnected and is never repeated, so the timer below runs whatever happens, and
 * {@link NotificationListener} runs the same pass on every connect and every reconnect before it
 * waits for anything. The signal only decides <em>when</em>, never <em>what</em> - the payload is
 * empty and the pass re-reads both queues in full.
 *
 * <h2>Why this does not contradict "serve is not a scheduler"</h2>
 * That rule is about versions: a crash restart at three in the morning must not move a jar. This
 * loop moves nothing and installs nothing - it asks a bank what has arrived and writes rows, which
 * is the same poll {@code discord-bot} has run on a timer since the first deployment. It is here
 * rather than there because the bunq key is here.
 *
 * <h2>One pass at a time</h2>
 * The timer thread and the listener thread both call {@link Payments#pass()}, and a pass makes HTTP
 * calls to a bank that can take a bounded but real amount of time. The lock is what keeps a
 * notification arriving mid-pass from starting a second one alongside it - which would not corrupt
 * anything (every write is guarded by the schema) but would ask bunq the same questions twice.
 * {@code tryLock} rather than {@code lock}: a pass that is already running is about to read the
 * same rows, so the second caller has nothing to add by waiting for it.
 */
@Slf4j
public final class PaymentLoop implements AutoCloseable {

    /**
     * One pass. A {@link Runnable} and not a {@link Payments}, for one reason: what is worth testing
     * about this class is that two callers produce one pass, and a {@code Payments} cannot be built
     * without a bunq gateway and a database. The public entry point below still takes the real type,
     * so nothing outside this file can pass something that is not the payment pass.
     */
    private final Runnable work;

    private final ScheduledExecutorService timer;
    private final ReentrantLock running = new ReentrantLock();

    /**
     * Set once, immediately after construction, and only because the listener has to be handed
     * {@code this::pass} - the two genuinely refer to each other. Volatile rather than final so
     * {@link #close()} on another thread sees it.
     */
    private volatile NotificationListener listener;

    PaymentLoop(final Runnable work, final ScheduledExecutorService timer) {
        this.work = work;
        this.timer = timer;
    }

    /**
     * Starts both halves.
     *
     * @param payments  the work
     * @param connector how to open a {@code LISTEN} connection; a dedicated one, never the pool's -
     *                  {@code LISTEN} is session state and a pool hands sessions back out
     * @param poll      how often to ask bunq regardless of any notification
     * @return the running loop, to be closed with the container
     */
    public static PaymentLoop start(final Payments payments,
                                    final Notifications.Connector connector,
                                    final Duration poll) {
        final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "steward-worker-bunq-poll");
            thread.setDaemon(true);
            return thread;
        });

        final PaymentLoop loop = new PaymentLoop(payments::pass, timer);
        loop.listener = new NotificationListener(
                connector,
                "steward-worker-bunq-listener",
                List.of(new NotificationListener.Refresh("the payment seam", loop::pass)),
                log,
                poll);
        loop.listener.start();

        // Zero initial delay: the first pass is what drains anything left over from the previous
        // container - a tab that was asked for while this one was being replaced produced a
        // notification nobody was connected to receive.
        timer.scheduleWithFixedDelay(loop::pass, 0, poll.toSeconds(), TimeUnit.SECONDS);
        return loop;
    }

    /**
     * One pass, unless one is already running.
     *
     * <p>Package-visible and not private so the test can call it without a bank, a database or a
     * clock: what is worth testing here is that two callers produce one pass.</p>
     */
    void pass() {
        if (!running.tryLock()) {
            log.debug("A payment pass is already running; this wake-up adds nothing");
            return;
        }
        try {
            work.run();
        } finally {
            running.unlock();
        }
    }

    /** The channel both halves of the seam wake on. */
    public static String channel() {
        return Channels.PAYMENT;
    }

    @Override
    public void close() {
        timer.shutdownNow();
        final NotificationListener open = listener;
        if (open != null) {
            open.close();
        }
    }
}
