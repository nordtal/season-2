package eu.nordtal.s2.database.notify;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Wakes one consumer that works on its own thread, for a {@link SignalHub} refresh that must not block the hub.
 * Rings that arrive while the consumer works collapse into one wake-up; the consumer re-reads in full anyway.
 */
public final class Doorbell {

    private boolean rung;

    /** Wakes the waiting consumer, or the next one to wait. */
    public synchronized void ring() {
        rung = true;
        notifyAll();
    }

    /**
     * Waits until the bell rings or the timeout runs out, and clears it.
     *
     * @return {@code true} if it rang, {@code false} on a plain timeout
     * @throws InterruptedException when the waiting thread is interrupted, which means stop
     */
    public synchronized boolean await(final Duration timeout) throws InterruptedException {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (!rung) {
            final long left = deadline - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            TimeUnit.NANOSECONDS.timedWait(this, left);
        }
        rung = false;
        return true;
    }
}
