package eu.nordtal.season.database.notify;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Wakes one consumer that works on its own thread. Rings during its work collapse into one wake-up. */
public final class Doorbell {

    private boolean rung;

    /** Wakes the consumer that waits, or the next one to wait. */
    public synchronized void ring() {
        rung = true;
        notifyAll();
    }

    /**
     * Waits until the bell rings or the timeout ends, and clears it.
     *
     * @return whether it rang
     * @throws InterruptedException if the thread is interrupted, which means stop
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
