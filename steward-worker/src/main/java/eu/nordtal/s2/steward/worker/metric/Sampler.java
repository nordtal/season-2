package eu.nordtal.s2.steward.worker.metric;

import eu.nordtal.s2.common.metric.MetricDirectory;
import eu.nordtal.s2.common.metric.MetricSample;
import eu.nordtal.s2.steward.worker.docker.Docker;
import eu.nordtal.s2.steward.worker.docker.DockerException;
import eu.nordtal.s2.steward.worker.host.HostMetrics;
import eu.nordtal.s2.steward.worker.host.HostSnapshot;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The clock behind the curves on the start page.
 *
 * <h2>What it writes and why that much</h2>
 * Every 30 seconds, for the host and for each container of the project. Counted for this host in
 * §10c: eleven series, 2 880 points per series per day, some 32 000 rows a day, about a million and
 * 60 MB after 30 days - after which raw samples become hourly means, which is the same month at a
 * thirtieth of the size and still a year of history.
 *
 * <p><b>That table is inside the backup.</b> Retention here is therefore also a decision about how
 * big every night's snapshot is, which is why the compaction runs on this clock rather than being
 * left to somebody to remember.</p>
 *
 * <h2>Why it is not on the request loop</h2>
 * One stats sample costs about a second of wall clock, because the daemon takes two readings to
 * give a real CPU delta (see {@link Docker#stats}). Ten of those in sequence would be ten seconds
 * of a thirty-second period spent waiting, so the containers are read in parallel on virtual
 * threads, and the whole thing runs on its own schedule. <b>A sampling failure never touches an
 * update run</b>: it is logged and the next tick tries again. Missing points in a chart are a
 * nuisance; a backup that did not happen because a chart failed is a disaster.
 */
public final class Sampler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Sampler.class);

    /** §10c: every 30 seconds. */
    public static final Duration PERIOD = Duration.ofSeconds(30);

    /** §10c: raw samples live 30 days, then they are hourly means. */
    public static final Duration RAW_RETENTION = Duration.ofDays(30);

    /** How long one round of sampling may take before it is abandoned for this tick. */
    private static final Duration ROUND_TIMEOUT = Duration.ofSeconds(20);

    private final Docker docker;
    private final HostMetrics host;
    private final MetricDirectory metrics;
    private final String project;

    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "metric-sampler");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService perContainer = Executors.newVirtualThreadPerTaskExecutor();

    public Sampler(final Docker docker, final HostMetrics host, final MetricDirectory metrics, final String project) {
        this.docker = docker;
        this.host = host;
        this.metrics = metrics;
        this.project = project;
    }

    /** Starts the clock. Sampling begins one period from now, not immediately. */
    public void start() {
        // One period of delay on purpose: the first CPU reading of the host has no previous one to
        // subtract from, so it would be an empty value anyway, and a stack that has just come up is
        // busy with things that are not representative of anything.
        clock.scheduleAtFixedRate(this::tickQuietly, PERIOD.toSeconds(), PERIOD.toSeconds(), TimeUnit.SECONDS);
        clock.scheduleAtFixedRate(this::compactQuietly, 1, 1, TimeUnit.HOURS);
        log.info("sampling host and container metrics every {}s", PERIOD.toSeconds());
    }

    private void tickQuietly() {
        try {
            final int written = tick(Instant.now());
            log.debug("wrote {} metric samples", written);
        } catch (RuntimeException e) {
            log.warn("a round of metric sampling failed; the next one will try again", e);
        }
    }

    /** One round. Visible for tests, which call it directly rather than waiting 30 seconds. */
    public int tick(final Instant at) {
        final List<MetricSample> samples = new ArrayList<>(hostSamples(at));
        samples.addAll(containerSamples(at));
        if (!samples.isEmpty()) {
            metrics.record(samples);
        }
        return samples.size();
    }

    private List<MetricSample> hostSamples(final Instant at) {
        final HostSnapshot snapshot;
        try {
            snapshot = host.read();
        } catch (IOException e) {
            log.warn("could not read the host's own numbers", e);
            return List.of();
        }
        final List<MetricSample> samples = new ArrayList<>();
        samples.add(new MetricSample("host", "load1", at, snapshot.load1()));
        snapshot.cpuPercent().ifPresent(percent -> samples.add(new MetricSample("host", "cpu_percent", at, percent)));
        samples.add(new MetricSample(
                "host", "memory_used_bytes", at, snapshot.memoryTotalBytes() - snapshot.memoryAvailableBytes()));
        samples.add(new MetricSample("host", "memory_total_bytes", at, snapshot.memoryTotalBytes()));
        samples.add(new MetricSample("host", "disk_used_bytes", at, snapshot.diskUsedBytes()));
        samples.add(new MetricSample("host", "disk_total_bytes", at, snapshot.diskTotalBytes()));
        return samples;
    }

    private List<MetricSample> containerSamples(final Instant at) {
        final List<Docker.Container> containers;
        try {
            containers = docker.containers(project).stream()
                    .filter(container -> container.service() != null && container.isRunning())
                    .toList();
        } catch (DockerException e) {
            log.warn("could not list containers for sampling", e);
            return List.of();
        }

        final List<Callable<Reading>> reads = containers.stream()
                .map(container -> (Callable<Reading>) () -> {
                    final Docker.Stats stats = docker.stats(container.id());
                    return new Reading(container.service(), stats.memoryBytes(), stats.cpuPercent());
                })
                .toList();

        final List<Reading> readings = new ArrayList<>();
        try {
            for (final Future<Reading> future :
                    perContainer.invokeAll(reads, ROUND_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                try {
                    readings.add(future.get());
                } catch (Exception e) {
                    // One container that would not answer is one gap in one chart, not a lost round.
                    log.debug("a container did not answer with stats", e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return byService(readings, at);
    }

    /** What one container answered, before it is a series. */
    record Reading(String service, long memoryBytes, java.util.OptionalDouble cpuPercent) {}

    /**
     * One sample per service and metric, however many containers that service is running.
     *
     * <h2>Two containers, one key</h2>
     * A metric row is keyed by {@code (subject, metric, resolution, at)} and written with
     * {@code ON CONFLICT DO NOTHING}, and the subject here is the <b>compose service</b>. Two
     * containers of one service in the same round therefore produce two rows with the same key, of
     * which the database silently keeps whichever arrived first: the chart would show one replica's
     * memory and call it the service's, and nothing anywhere would say a number had been dropped.
     *
     * <p>This stack runs one container per service and the deployer never scales anything, so today
     * that is a fold over lists of one. It is here because the failure it prevents is invisible:
     * somebody trying {@code --scale smp=2} for an afternoon would get a graph that is quietly
     * wrong rather than one that is obviously broken.</p>
     *
     * <p>Memory adds up and so does CPU - both are "what this service is using on this host", and a
     * percentage that is already relative to the whole host stays meaningful when summed. A service
     * whose containers gave no CPU reading at all gets no CPU sample rather than a zero, for the
     * reason {@link HostSnapshot#cpuPercent()} gives: a chart that opens at zero because nothing was
     * measured is a lie the page then inherits.</p>
     */
    static List<MetricSample> byService(final List<Reading> readings, final Instant at) {
        final Map<String, Double> memory = new LinkedHashMap<>();
        final Map<String, Double> cpu = new LinkedHashMap<>();
        for (final Reading reading : readings) {
            memory.merge(reading.service(), (double) reading.memoryBytes(), Double::sum);
            reading.cpuPercent().ifPresent(percent -> cpu.merge(reading.service(), percent, Double::sum));
        }
        final List<MetricSample> samples = new ArrayList<>();
        memory.forEach((service, bytes) -> samples.add(new MetricSample(service, "memory_bytes", at, bytes)));
        cpu.forEach((service, percent) -> samples.add(new MetricSample(service, "cpu_percent", at, percent)));
        return samples;
    }

    private void compactQuietly() {
        try {
            final Instant boundary = Instant.now().minus(RAW_RETENTION);
            final int written = metrics.compact(boundary);
            final int forgotten = metrics.forget(boundary);
            if (written > 0 || forgotten > 0) {
                log.info(
                        "compacted {} hours of samples and forgot {} raw rows older than {}",
                        written,
                        forgotten,
                        boundary);
            }
        } catch (RuntimeException e) {
            log.warn("compacting the metric table failed; it will be tried again in an hour", e);
        }
    }

    @Override
    public void close() {
        clock.shutdownNow();
        perContainer.shutdownNow();
    }
}
