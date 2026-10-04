package eu.nordtal.s2.common.time;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;

/** {@link Scheduler#serial()}: one task at a time, each handed to the scheduler when the one before it ended. */
final class SerialLane implements Executor {

    private final Executor threads;
    private final Queue<Runnable> waiting = new ArrayDeque<>();
    private boolean running;

    SerialLane(final Executor threads) {
        this.threads = threads;
    }

    @Override
    public synchronized void execute(final Runnable work) {
        waiting.add(work);
        if (!running) {
            running = true;
            threads.execute(this::runNext);
        }
    }

    private void runNext() {
        final Runnable next;
        synchronized (this) {
            next = waiting.poll();
            if (next == null) {
                running = false;
                return;
            }
        }
        try {
            next.run();
        } finally {
            // A throw is the scheduler's to log; the lane goes on either way.
            threads.execute(this::runNext);
        }
    }
}
