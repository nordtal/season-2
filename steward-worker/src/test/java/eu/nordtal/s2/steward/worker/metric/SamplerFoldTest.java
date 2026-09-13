package eu.nordtal.s2.steward.worker.metric;

import eu.nordtal.s2.common.metric.MetricSample;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The step between "what each container answered" and "what the chart is keyed by". */
class SamplerFoldTest {

    private static final Instant AT = Instant.parse("2026-09-13T04:45:00Z");

    @Test
    @DisplayName("one container per service is one sample per metric, unchanged")
    void theOrdinaryCase() {
        final List<MetricSample> samples = Sampler.byService(List.of(
                new Sampler.Reading("smp", 2_000_000_000L, OptionalDouble.of(42.5)),
                new Sampler.Reading("postgres", 300_000_000L, OptionalDouble.of(1.5))), AT);

        assertEquals(4, samples.size());
        assertEquals(2_000_000_000.0, valueOf(samples, "smp", "memory_bytes"));
        assertEquals(42.5, valueOf(samples, "smp", "cpu_percent"));
        assertEquals(300_000_000.0, valueOf(samples, "postgres", "memory_bytes"));
    }

    @Test
    @DisplayName("two containers of one service are one sample, not one of them silently kept")
    void replicasAreAddedUpRatherThanDropped() {
        // (subject, metric, resolution, at) is the primary key and record() is ON CONFLICT DO
        // NOTHING: two rows with the same key mean one of the two numbers vanishes into the
        // database without a word, and the chart then shows one replica and calls it the service.
        final List<MetricSample> samples = Sampler.byService(List.of(
                new Sampler.Reading("smp", 2_000_000_000L, OptionalDouble.of(40.0)),
                new Sampler.Reading("smp", 1_000_000_000L, OptionalDouble.of(15.0))), AT);

        assertEquals(2, samples.size(), samples.toString());
        assertEquals(3_000_000_000.0, valueOf(samples, "smp", "memory_bytes"));
        assertEquals(55.0, valueOf(samples, "smp", "cpu_percent"));
    }

    @Test
    @DisplayName("a container with no CPU reading yet gets no CPU sample, rather than a zero")
    void nothingMeasuredIsNotZero() {
        final List<MetricSample> samples = Sampler.byService(
                List.of(new Sampler.Reading("limbo", 500_000_000L, OptionalDouble.empty())), AT);

        assertEquals(List.of("memory_bytes"), samples.stream().map(MetricSample::metric).toList());
    }

    @Test
    void nothingRunningIsNoSamples() {
        assertTrue(Sampler.byService(List.of(), AT).isEmpty());
    }

    private static double valueOf(final List<MetricSample> samples, final String subject,
                                  final String metric) {
        return samples.stream()
                .filter(sample -> sample.subject().equals(subject) && sample.metric().equals(metric))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + metric + " for " + subject))
                .value();
    }
}
