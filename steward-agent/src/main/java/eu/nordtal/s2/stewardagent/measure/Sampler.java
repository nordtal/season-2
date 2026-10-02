package eu.nordtal.s2.stewardagent.measure;

import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.stewardagent.docker.Docker;
import eu.nordtal.s2.stewardagent.docker.DockerException;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the host and every running container every 30 seconds, the only place {@code docker stats} is asked.
 *
 * The rounds wait in memory until steward collects them; the service table and the alerts read the newest one.
 */
public final class Sampler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Sampler.class);

    /** Every 30 seconds. */
    public static final Duration PERIOD = Duration.ofSeconds(30);

    /** An hour of rounds, so a steward that restarts within it misses none. */
    static final int KEPT = 120;

    /** How long one round may take before it is abandoned for this tick. */
    private static final Duration ROUND_TIMEOUT = Duration.ofSeconds(20);

    private final Docker docker;
    private final HostMetrics host;
    private final String project;
    private final Clock wall;

    private final Deque<AgentWire.Round> rounds = new ArrayDeque<>();

    private final ScheduledExecutorService ticks = Executors.newSingleThreadScheduledExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "metric-sampler");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService perContainer = Executors.newVirtualThreadPerTaskExecutor();

    public Sampler(final Docker docker, final HostMetrics host, final String project, final Clock clock) {
        this.docker = docker;
        this.host = host;
        this.project = project;
        this.wall = Objects.requireNonNull(clock, "clock");
    }

    /** Takes a round now, which has no CPU yet, and one every period after it. */
    public void start() {
        final var _ = ticks.scheduleAtFixedRate(this::tickQuietly, 0, PERIOD.toSeconds(), TimeUnit.SECONDS);
        log.info("sampling the host and the containers every {}s", PERIOD.toSeconds());
    }

    private void tickQuietly() {
        try {
            tick(wall.instant());
        } catch (final RuntimeException e) {
            log.warn("a round of sampling failed; the next one will try again", e);
        }
    }

    /** One round, kept; tests call it directly. */
    public AgentWire.Round tick(final Instant at) {
        final AgentWire.Round round = new AgentWire.Round(at, hostNumbers(), byService(readings(), at));
        synchronized (rounds) {
            rounds.addLast(round);
            while (rounds.size() > KEPT) {
                rounds.removeFirst();
            }
        }
        return round;
    }

    /** The rounds taken after {@code after}, oldest first; {@code null} asks for every round still held. */
    public List<AgentWire.Round> after(final @Nullable Instant after) {
        synchronized (rounds) {
            return rounds.stream()
                    .filter(round -> after == null || round.at().isAfter(after))
                    .toList();
        }
    }

    /** The newest round, or {@code null} before the first one. */
    public AgentWire.@Nullable Round latest() {
        synchronized (rounds) {
            return rounds.peekLast();
        }
    }

    /** The newest reading of each service, empty before the first round. */
    public Map<String, AgentWire.Reading> latestByService() {
        final AgentWire.Round round = latest();
        return round == null ? Map.of() : round.services();
    }

    private AgentWire.@Nullable HostNumbers hostNumbers() {
        try {
            final HostSnapshot snapshot = host.read();
            return new AgentWire.HostNumbers(
                    snapshot.load1(),
                    snapshot.cpus(),
                    snapshot.cpuPercent().isPresent() ? snapshot.cpuPercent().getAsDouble() : null,
                    snapshot.memoryTotalBytes(),
                    snapshot.memoryAvailableBytes(),
                    snapshot.diskTotalBytes(),
                    snapshot.diskUsedBytes());
        } catch (final IOException e) {
            log.warn("could not read the host's own numbers", e);
            return null;
        }
    }

    private List<Reading> readings() {
        final List<Docker.Container> containers;
        try {
            containers = docker.containers(project).stream()
                    .filter(container -> container.service() != null && container.isRunning())
                    .toList();
        } catch (final DockerException e) {
            log.warn("could not list containers for sampling", e);
            return List.of();
        }
        final List<Callable<Reading>> reads = containers.stream()
                .map(container -> (Callable<Reading>) () -> {
                    final Docker.Stats stats = docker.stats(container.id());
                    final String service =
                            Objects.requireNonNull(container.service(), "containers is filtered to service() != null");
                    return new Reading(service, stats.memoryBytes(), stats.memoryLimitBytes(), stats.cpuPercent());
                })
                .toList();
        final List<Reading> readings = new ArrayList<>();
        try {
            for (final Future<Reading> future :
                    perContainer.invokeAll(reads, ROUND_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                try {
                    readings.add(future.get());
                } catch (final Exception e) {
                    // One container that would not answer is one gap in one chart, not a lost round.
                    log.debug("a container did not answer with stats", e);
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return readings;
    }

    /** What one container answered, before it is folded into its service. */
    record Reading(String service, long memoryBytes, long memoryLimitBytes, OptionalDouble cpuPercent) {}

    /**
     * One reading per service, summed over its containers.
     *
     * A service whose containers gave no CPU reading has no CPU rather than a zero.
     */
    static Map<String, AgentWire.Reading> byService(final List<Reading> readings, final Instant at) {
        final Map<String, long[]> memory = new LinkedHashMap<>();
        final Map<String, Double> cpu = new LinkedHashMap<>();
        for (final Reading reading : readings) {
            final long[] sums = memory.computeIfAbsent(reading.service(), service -> new long[2]);
            sums[0] += reading.memoryBytes();
            sums[1] += reading.memoryLimitBytes();
            reading.cpuPercent().ifPresent(percent -> cpu.merge(reading.service(), percent, Double::sum));
        }
        final Map<String, AgentWire.Reading> services = new LinkedHashMap<>();
        memory.forEach((service, sums) ->
                services.put(service, new AgentWire.Reading(at, sums[0], sums[1], cpu.get(service))));
        return services;
    }

    @Override
    public void close() {
        ticks.shutdownNow();
        perContainer.shutdownNow();
    }
}
