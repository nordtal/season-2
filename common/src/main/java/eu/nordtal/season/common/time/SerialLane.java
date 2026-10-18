package eu.nordtal.season.common.time;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;

/** {@link Scheduler#serial()}: runs one task at a time. */
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
            threads.execute(this::runNext);
        }
    }
}
