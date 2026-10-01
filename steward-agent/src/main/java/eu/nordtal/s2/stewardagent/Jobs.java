package eu.nordtal.s2.stewardagent;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs deployments as jobs, one at a time, so a request answers at once and the output is read as it appears.
 *
 * Jobs live in memory and die with this container; the stack itself is the durable record.
 */
public final class Jobs {

    private static final Logger log = LoggerFactory.getLogger(Jobs.class);

    /** How many finished jobs are kept. */
    private static final int KEEP = 50;

    private final Clock clock;
    private final Map<String, Job> byId = new ConcurrentHashMap<>();
    private final List<String> order = new CopyOnWriteArrayList<>();

    /** How many deployments may wait behind the running one before a new one is refused. */
    private static final int WAITING = 5;

    /** One at a time, since two compose runs against one project race for the same containers. */
    private final ExecutorService worker =
            new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(WAITING), runnable -> {
                final Thread thread = new Thread(runnable, "agent-job");
                thread.setDaemon(true);
                return thread;
            });

    public Jobs(final Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public Job start(final String kind, final List<String> services, final Work work) {
        final Job job = new Job(UUID.randomUUID().toString(), kind, List.copyOf(services), clock);
        byId.put(job.id(), job);
        order.add(job.id());
        forget();
        try {
            submit(job, work);
        } catch (RejectedExecutionException full) {
            job.append("refused: " + WAITING + " deployments are already waiting behind the one"
                    + " that is running. Wait for them, or read /api/jobs to see what they are.");
            job.finish(-1);
        }
        return job;
    }

    private void submit(final Job job, final Work work) {
        // The runnable catches every exception itself.
        final var _ = worker.submit(() -> {
            try {
                final int code = work.run(job::append);
                job.finish(code);
            } catch (Exception e) {
                log.error("job {} ({}) failed", job.id(), job.kind(), e);
                job.append(e.getClass().getSimpleName() + ": " + e.getMessage());
                job.finish(-1);
            }
        });
    }

    public @Nullable Job get(final String id) {
        return byId.get(id);
    }

    public Collection<Job> all() {
        return order.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    private void forget() {
        while (order.size() > KEEP) {
            final String oldest = order.remove(0);
            final Job job = byId.get(oldest);
            if (job != null && job.state() == State.RUNNING) {
                // Never drop a running job: its listeners would stop being fed.
                order.add(oldest);
                return;
            }
            byId.remove(oldest);
        }
    }

    public interface Work {
        int run(Consumer<String> output) throws Exception;
    }

    public enum State {
        RUNNING,
        DONE,
        FAILED
    }

    /** One deployment, its output so far, and whoever is currently watching it. */
    public static final class Job {

        private final String id;
        private final String kind;
        private final List<String> services;
        private final Clock clock;
        private final Instant started;
        private final List<String> lines = new CopyOnWriteArrayList<>();
        private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
        /** Guards writing a line against starting to watch, so a reconnect never misses one. */
        private final Object watchers = new Object();

        private volatile State state = State.RUNNING;
        private volatile int exitCode = Integer.MIN_VALUE;
        private volatile @Nullable Instant finished;

        Job(final String id, final String kind, final List<String> services, final Clock clock) {
            this.clock = clock;
            this.started = clock.instant();
            this.id = id;
            this.kind = kind;
            this.services = services;
        }

        /**
         * Writes one line to the record and to everyone watching, under {@link #watchers}.
         *
         * A listener that throws is dropped, since a blocking one would stall the job's output.
         */
        void append(final String line) {
            synchronized (watchers) {
                lines.add(line);
                for (final Consumer<String> listener : List.copyOf(listeners)) {
                    try {
                        listener.accept(line);
                    } catch (RuntimeException e) {
                        listeners.remove(listener);
                    }
                }
            }
        }

        void finish(final int code) {
            exitCode = code;
            finished = clock.instant();
            // Write the line before flipping the state: follow() stops once state leaves RUNNING.
            final State finalState = code == 0 ? State.DONE : State.FAILED;
            synchronized (watchers) {
                append("--- " + finalState + " (exit " + code + ")");
                state = finalState;
            }
        }

        /** Feeds everything written so far, then every further line. */
        public Runnable follow(final Consumer<String> listener) {
            synchronized (watchers) {
                for (final String line : new ArrayList<>(lines)) {
                    listener.accept(line);
                }
                if (state != State.RUNNING) {
                    return () -> {};
                }
                listeners.add(listener);
            }
            return () -> listeners.remove(listener);
        }

        public String id() {
            return id;
        }

        public String kind() {
            return kind;
        }

        public List<String> services() {
            return services;
        }

        public State state() {
            return state;
        }

        public List<String> lines() {
            return List.copyOf(lines);
        }

        public Map<String, Object> summary() {
            final Map<String, Object> summary = new java.util.LinkedHashMap<>();
            summary.put("id", id);
            summary.put("kind", kind);
            summary.put("services", services);
            summary.put("state", state.name());
            summary.put("started", started.toString());
            if (finished != null) {
                summary.put("finished", finished.toString());
                summary.put("exitCode", exitCode);
            }
            return summary;
        }
    }
}
