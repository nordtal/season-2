package eu.nordtal.s2.steward.metric;

import eu.nordtal.s2.common.time.Scheduler;
import eu.nordtal.s2.database.metric.Metric;
import eu.nordtal.s2.database.metric.MetricDirectory;
import eu.nordtal.s2.database.metric.MetricSample;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the agent's sampler rounds into {@code metric_sample} for the start page's curves, and compacts old ones.
 *
 * steward-agent samples; this only copies what it took since the last copy, so a restart of either loses nothing.
 */
public final class MetricRecorder implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MetricRecorder.class);

    private static final String HOST = "host";

    private final Function<@Nullable Instant, List<AgentWire.Round>> rounds;
    private final MetricDirectory metrics;
    private final Clock clock;

    /** The newest round already written; {@code null} until the first copy, which takes all the agent holds. */
    private @Nullable Instant copied;

    private final List<Scheduler.Task> ticks = new ArrayList<>();

    /** @param rounds the agent's rounds taken after an instant, or all of them for {@code null} */
    public MetricRecorder(
            final Function<@Nullable Instant, List<AgentWire.Round>> rounds,
            final MetricDirectory metrics,
            final Clock clock) {
        this.rounds = rounds;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Copies every {@link MetricDirectory#SAMPLE_INTERVAL} and compacts every hour on the process's scheduler. */
    public synchronized void start(final Scheduler scheduler) {
        ticks.add(scheduler.every(Duration.ZERO, MetricDirectory.SAMPLE_INTERVAL, this::copyQuietly));
        ticks.add(scheduler.every(Duration.ofHours(1), Duration.ofHours(1), this::compactQuietly));
    }

    private void copyQuietly() {
        try {
            log.debug("wrote {} metric samples", copy());
        } catch (final RuntimeException e) {
            log.debug("the agent's samples could not be copied; the next round will try again", e);
        }
    }

    /** Copies every round taken since the last copy; a round written twice is still one row per series. */
    public synchronized int copy() {
        final List<AgentWire.Round> taken = rounds.apply(copied);
        final List<MetricSample> samples = new ArrayList<>();
        for (final AgentWire.Round round : taken) {
            samples.addAll(samples(round));
        }
        if (!samples.isEmpty()) {
            metrics.record(samples);
        }
        if (!taken.isEmpty()) {
            copied = taken.getLast().at();
        }
        return samples.size();
    }

    /** One round as rows: the host's numbers and each service's memory and CPU. */
    static List<MetricSample> samples(final AgentWire.Round round) {
        final Instant at = round.at();
        final List<MetricSample> samples = new ArrayList<>();
        final AgentWire.HostNumbers host = round.host();
        if (host != null) {
            samples.add(new MetricSample(HOST, Metric.LOAD1, at, host.load1()));
            if (host.cpuPercent() != null) {
                samples.add(new MetricSample(HOST, Metric.CPU_PERCENT, at, host.cpuPercent()));
            }
            samples.add(new MetricSample(
                    HOST, Metric.MEMORY_USED_BYTES, at, host.memoryTotalBytes() - host.memoryAvailableBytes()));
            samples.add(new MetricSample(HOST, Metric.MEMORY_TOTAL_BYTES, at, host.memoryTotalBytes()));
            samples.add(new MetricSample(HOST, Metric.DISK_USED_BYTES, at, host.diskUsedBytes()));
            samples.add(new MetricSample(HOST, Metric.DISK_TOTAL_BYTES, at, host.diskTotalBytes()));
        }
        for (final Map.Entry<String, AgentWire.Reading> service :
                round.services().entrySet()) {
            final AgentWire.Reading reading = service.getValue();
            samples.add(new MetricSample(service.getKey(), Metric.MEMORY_BYTES, at, reading.memoryBytes()));
            if (reading.cpuPercent() != null) {
                samples.add(new MetricSample(service.getKey(), Metric.CPU_PERCENT, at, reading.cpuPercent()));
            }
        }
        return samples;
    }

    private void compactQuietly() {
        try {
            final Instant boundary = clock.instant().minus(MetricDirectory.RAW_RETENTION);
            final int written = metrics.compact(boundary);
            final int forgotten = metrics.forget(boundary);
            if (written > 0 || forgotten > 0) {
                log.info(
                        "compacted {} hours of samples and forgot {} raw rows older than {}",
                        written,
                        forgotten,
                        boundary);
            }
        } catch (final RuntimeException e) {
            log.warn("compacting the metric table failed; it will be tried again in an hour", e);
        }
    }

    @Override
    public synchronized void close() {
        ticks.forEach(Scheduler.Task::cancel);
        ticks.clear();
    }
}
