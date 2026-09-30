package eu.nordtal.s2.steward.worker.metric;

import eu.nordtal.s2.database.metric.MetricDirectory;
import eu.nordtal.s2.database.metric.MetricSample;
import eu.nordtal.s2.steward.worker.docker.Docker;
import eu.nordtal.s2.steward.worker.docker.DockerException;
import eu.nordtal.s2.steward.worker.host.HostMetrics;
import eu.nordtal.s2.steward.worker.host.HostSnapshot;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Samples the host and each container every 30 seconds for the start page's charts, and compacts old samples.
 *
 * Containers are read in parallel on virtual threads; a failed sample is logged and never touches an update run.
 */
public final class Sampler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Sampler.class);

    /** Every 30 seconds. */
    public static final Duration PERIOD = Duration.ofSeconds(30);

    /** Raw samples live 30 days, then they become hourly means. */
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

    private final Clock wall;

    public Sampler(
            final Docker docker,
            final HostMetrics host,
            final MetricDirectory metrics,
            final String project,
            final Clock clock) {
        this.wall = java.util.Objects.requireNonNull(clock, "clock");
        this.docker = docker;
        this.host = host;
        this.metrics = metrics;
        this.project = project;
    }

    /** Starts the clock; sampling begins one period from now. */
    public void start() {
        // The first CPU reading has nothing to subtract from yet.
        final var _ =
                clock.scheduleAtFixedRate(this::tickQuietly, PERIOD.toSeconds(), PERIOD.toSeconds(), TimeUnit.SECONDS);
        final var _ = clock.scheduleAtFixedRate(this::compactQuietly, 1, 1, TimeUnit.HOURS);
        log.info("sampling host and container metrics every {}s", PERIOD.toSeconds());
    }

    private void tickQuietly() {
        try {
            final int written = tick(wall.instant());
            log.debug("wrote {} metric samples", written);
        } catch (RuntimeException e) {
            log.warn("a round of metric sampling failed; the next one will try again", e);
        }
    }

    /** One round of sampling; tests call it directly. */
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
                    final String service =
                            Objects.requireNonNull(container.service(), "containers is filtered to service() != null");
                    return new Reading(service, stats.memoryBytes(), stats.cpuPercent());
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
     * One sample per service and metric, summed over its containers, since a second row with the same key is dropped.
     *
     * A service whose containers gave no CPU reading gets no CPU sample rather than a zero.
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
            final Instant boundary = wall.instant().minus(RAW_RETENTION);
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
