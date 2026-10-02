package eu.nordtal.s2.steward.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.database.metric.Metric;
import eu.nordtal.s2.database.metric.MetricDirectory;
import eu.nordtal.s2.database.metric.MetricPoint;
import eu.nordtal.s2.database.metric.MetricSample;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The recorder copies each of the agent's rounds once, asking after the newest it already wrote. */
class MetricRecorderTest {

    private static final Instant FIRST = Instant.parse("2026-10-02T01:00:00Z");
    private static final Instant SECOND = FIRST.plusSeconds(30);

    private final List<@Nullable Instant> asked = new ArrayList<>();
    private final List<MetricSample> written = new ArrayList<>();
    private List<AgentWire.Round> held = List.of();

    private final MetricRecorder recorder = new MetricRecorder(
            after -> {
                asked.add(after);
                return held.stream()
                        .filter(round -> after == null || round.at().isAfter(after))
                        .toList();
            },
            new Recording(),
            Clock.fixed(FIRST, ZoneOffset.UTC));

    @Test
    void eachRoundIsWrittenOnceAndTheNextCopyAsksAfterTheNewest() {
        held = List.of(round(FIRST));
        final int first = recorder.copy();
        held = List.of(round(FIRST), round(SECOND));
        final int second = recorder.copy();
        final int third = recorder.copy();

        assertEquals(Arrays.asList(null, FIRST, SECOND), asked);
        assertEquals(first, second, "the second copy wrote the first round again, or skipped the second");
        assertEquals(0, third);
        assertEquals(2 * first, written.size());
    }

    @Test
    void aRoundBecomesTheHostsNumbersAndEachServicesMemoryAndCpu() {
        final List<MetricSample> samples = MetricRecorder.samples(round(FIRST));

        assertTrue(samples.contains(new MetricSample("host", Metric.MEMORY_USED_BYTES, FIRST, 600)));
        assertTrue(samples.contains(new MetricSample("smp", Metric.MEMORY_BYTES, FIRST, 2048)));
        assertTrue(samples.contains(new MetricSample("smp", Metric.CPU_PERCENT, FIRST, 12.5)));
        // No CPU yet: the agent has one reading of postgres, and a rate needs two.
        assertEquals(
                List.of(new MetricSample("postgres", Metric.MEMORY_BYTES, FIRST, 512)),
                samples.stream()
                        .filter(sample -> sample.subject().equals("postgres"))
                        .toList());
    }

    private static AgentWire.Round round(final Instant at) {
        return new AgentWire.Round(
                at,
                new AgentWire.HostNumbers(0.5, 4, 20.0, 1000, 400, 10_000, 2_500),
                Map.of(
                        "smp", new AgentWire.Reading(at, 2048, 4096, 12.5),
                        "postgres", new AgentWire.Reading(at, 512, 1024, null)));
    }

    private final class Recording implements MetricDirectory {

        @Override
        public void record(final List<MetricSample> samples) {
            written.addAll(samples);
        }

        @Override
        public List<MetricPoint> range(
                final String subject, final String metric, final Instant from, final Instant to) {
            return List.of();
        }

        @Override
        public int compact(final Instant olderThan) {
            return 0;
        }

        @Override
        public int forget(final Instant olderThan) {
            return 0;
        }
    }
}
