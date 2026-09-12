package eu.nordtal.s2.steward.deployer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * A deployment is slow, so it is a job rather than a request.
 *
 * <p>Pulling four images and recreating a stack takes minutes; an HTTP call that waits for it times
 * out somewhere nobody controls, and the caller is then left not knowing whether the work
 * continued. So a request starts a job and answers immediately with its id, and the interface reads
 * the output as it appears.</p>
 *
 * <p><b>Jobs live in memory and die with this container.</b> That is a deliberate limit, not an
 * oversight: the durable record of a deployment is the state of the stack itself plus this
 * service's log, and a second store would be another thing to back up and keep consistent. A job
 * whose answer nobody read is a job whose result can be read off {@code docker compose ps}.</p>
 */
public final class Jobs {

    private static final Logger log = LoggerFactory.getLogger(Jobs.class);

    /** How many finished jobs are kept. Small on purpose - see the class comment. */
    private static final int KEEP = 50;

    private final Map<String, Job> byId = new ConcurrentHashMap<>();
    private final List<String> order = new CopyOnWriteArrayList<>();

    /** One at a time. Two compose runs against one project race for the same containers. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "deployer-job");
        thread.setDaemon(true);
        return thread;
    });

    public Job start(String kind, List<String> services, Work work) {
        Job job = new Job(UUID.randomUUID().toString(), kind, List.copyOf(services));
        byId.put(job.id(), job);
        order.add(job.id());
        forget();
        worker.submit(() -> {
            try {
                int code = work.run(job::append);
                job.finish(code);
            } catch (Exception e) {
                log.error("job {} ({}) failed", job.id(), kind, e);
                job.append(e.getClass().getSimpleName() + ": " + e.getMessage());
                job.finish(-1);
            }
        });
        return job;
    }

    public Job get(String id) {
        return byId.get(id);
    }

    public Collection<Job> all() {
        return order.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    private void forget() {
        while (order.size() > KEEP) {
            String oldest = order.remove(0);
            Job job = byId.get(oldest);
            if (job != null && job.state() == State.RUNNING) {
                // Never drop a job that is still running: its listeners would stop being fed and
                // the caller would see a deployment that simply stopped saying anything.
                order.add(oldest);
                return;
            }
            byId.remove(oldest);
        }
    }

    public interface Work {
        int run(Consumer<String> output) throws Exception;
    }

    public enum State { RUNNING, DONE, FAILED }

    /** One deployment, its output so far, and whoever is currently watching it. */
    public static final class Job {

        private final String id;
        private final String kind;
        private final List<String> services;
        private final Instant started = Instant.now();
        private final List<String> lines = new CopyOnWriteArrayList<>();
        private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

        private volatile State state = State.RUNNING;
        private volatile int exitCode = Integer.MIN_VALUE;
        private volatile Instant finished;

        Job(String id, String kind, List<String> services) {
            this.id = id;
            this.kind = kind;
            this.services = services;
        }

        void append(String line) {
            lines.add(line);
            for (Consumer<String> listener : listeners) {
                try {
                    listener.accept(line);
                } catch (RuntimeException e) {
                    // A browser that walked away must not take the deployment with it.
                    listeners.remove(listener);
                }
            }
        }

        void finish(int code) {
            exitCode = code;
            finished = Instant.now();
            // THE ORDER IS THE POINT. follow() stops registering a listener once the state has left
            // RUNNING, so a closing line written after that flip reaches nobody who attached in
            // between - and the job then looks, to that one caller, like a deployment that simply
            // stopped talking. Write the line first, flip afterwards.
            State finalState = code == 0 ? State.DONE : State.FAILED;
            append("--- " + finalState + " (exit " + code + ")");
            state = finalState;
        }

        /**
         * Feeds everything written so far, then every further line.
         *
         * <p>The replay is what makes a reconnect honest: a listener that only ever sees the future
         * shows a deployment that appears to begin in the middle.</p>
         */
        public Runnable follow(Consumer<String> listener) {
            for (String line : new ArrayList<>(lines)) {
                listener.accept(line);
            }
            if (state != State.RUNNING) {
                return () -> { };
            }
            listeners.add(listener);
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
            Map<String, Object> summary = new java.util.LinkedHashMap<>();
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
