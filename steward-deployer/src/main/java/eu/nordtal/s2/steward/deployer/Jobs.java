package eu.nordtal.s2.steward.deployer;

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
 * A deployment is slow, so it is a job rather than a request.
 *
 * Pulling four images and recreating a stack takes minutes; an HTTP call that waits for it times
 * out somewhere nobody controls, and the caller is then left not knowing whether the work
 * continued. So a request starts a job and answers immediately with its id, and the interface
 * reads the output as it appears.
 *
 * <b>Jobs live in memory and die with this container.</b> That is a deliberate limit, not an
 * oversight: the durable record of a deployment is the state of the stack itself plus this
 * service's log, and a second store would be another thing to back up and keep consistent. A job
 * whose answer nobody read is a job whose result can be read off {@code docker compose ps}.
 */
public final class Jobs {

    private static final Logger log = LoggerFactory.getLogger(Jobs.class);

    /** How many finished jobs are kept. Small on purpose - see the class comment. */
    private static final int KEEP = 50;

    private final Map<String, Job> byId = new ConcurrentHashMap<>();
    private final List<String> order = new CopyOnWriteArrayList<>();

    /**
     * How many deployments may be waiting behind the one that is running.
     *
     * Small on purpose: an unbounded queue lets a browser holding a button down stack up hundreds
     * of deployments that each stay {@code RUNNING}, keep their output forever, and then run one
     * after another for hours against a stack nobody is still asking about. Five is more than
     * anybody deploys on purpose and small enough that the sixth is obviously a mistake.
     */
    private static final int WAITING = 5;

    /** One at a time. Two compose runs against one project race for the same containers. */
    private final ExecutorService worker =
            new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(WAITING), runnable -> {
                final Thread thread = new Thread(runnable, "deployer-job");
                thread.setDaemon(true);
                return thread;
            });

    public Job start(final String kind, final List<String> services, final Work work) {
        final Job job = new Job(UUID.randomUUID().toString(), kind, List.copyOf(services));
        byId.put(job.id(), job);
        order.add(job.id());
        forget();
        try {
            submit(job, work);
        } catch (RejectedExecutionException full) {
            // A refusal the caller can read beats a queue that grows without limit.
            job.append("refused: " + WAITING + " deployments are already waiting behind the one"
                    + " that is running. Wait for them, or read /api/jobs to see what they are.");
            job.finish(-1);
        }
        return job;
    }

    private void submit(final Job job, final Work work) {
        // The runnable catches every exception itself, so the future's own result carries nothing new.
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
                // Never drop a job that is still running: its listeners would stop being fed.
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
        private final Instant started = Instant.now();
        private final List<String> lines = new CopyOnWriteArrayList<>();
        private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
        /** Guards "write a line" against "start watching", so neither can happen inside the other. */
        private final Object watchers = new Object();

        private volatile State state = State.RUNNING;
        private volatile int exitCode = Integer.MIN_VALUE;
        private volatile @Nullable Instant finished;

        Job(final String id, final String kind, final List<String> services) {
            this.id = id;
            this.kind = kind;
            this.services = services;
        }

        /**
         * One line, to the record and to everyone watching.
         *
         * <b>The lock is what makes a reconnect honest.</b> {@link #follow} copies the lines it
         * has and then registers; a line written in that gap would otherwise reach neither the
         * copy nor the new listener, lost for that caller for good - most likely the closing line,
         * which would make the deployment look, to whoever had just reconnected, like one that
         * simply stopped talking.
         *
         * Listeners are fed while the lock is held, which is deliberate: feeding them outside it
         * restores the gap in a different shape, as lines arriving out of order. What it costs is
         * that a listener which blocks blocks this job's output - so a listener that throws is
         * dropped, and the ones this service has are SSE writes to a local reverse proxy.
         */
        void append(final String line) {
            synchronized (watchers) {
                lines.add(line);
                for (final Consumer<String> listener : List.copyOf(listeners)) {
                    try {
                        listener.accept(line);
                    } catch (RuntimeException e) {
                        // A browser that walked away must not take the deployment with it.
                        listeners.remove(listener);
                    }
                }
            }
        }

        void finish(final int code) {
            exitCode = code;
            finished = Instant.now();
            // Write the line before flipping the state: follow() stops listening once state leaves RUNNING.
            final State finalState = code == 0 ? State.DONE : State.FAILED;
            synchronized (watchers) {
                append("--- " + finalState + " (exit " + code + ")");
                state = finalState;
            }
        }

        /**
         * Feeds everything written so far, then every further line.
         *
         * The replay is what makes a reconnect honest: a listener that only ever sees the future
         * shows a deployment that appears to begin in the middle.
         */
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
