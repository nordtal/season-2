package eu.nordtal.s2.steward.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.database.metric.MetricSample;
import java.time.Instant;
import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

/** The step between "what each container answered" and "what the chart is keyed by". */
class SamplerFoldTest {

    private static final Instant AT = Instant.parse("2026-09-13T04:45:00Z");

    @Test
    void oneContainerPerServiceIsOneSamplePerMetricUnchanged() {
        final List<MetricSample> samples = Sampler.byService(
                List.of(
                        new Sampler.Reading("smp", 2_000_000_000L, OptionalDouble.of(42.5)),
                        new Sampler.Reading("postgres", 300_000_000L, OptionalDouble.of(1.5))),
                AT);

        assertEquals(4, samples.size());
        assertEquals(2_000_000_000.0, valueOf(samples, "smp", "memory_bytes"));
        assertEquals(42.5, valueOf(samples, "smp", "cpu_percent"));
        assertEquals(300_000_000.0, valueOf(samples, "postgres", "memory_bytes"));
    }

    @Test
    void twoContainersOfOneServiceAreOneSampleNotOneOfThemSilentlyKept() {
        // record() is ON CONFLICT DO NOTHING on the primary key, so a duplicate row's number vanishes without a word.
        final List<MetricSample> samples = Sampler.byService(
                List.of(
                        new Sampler.Reading("smp", 2_000_000_000L, OptionalDouble.of(40.0)),
                        new Sampler.Reading("smp", 1_000_000_000L, OptionalDouble.of(15.0))),
                AT);

        assertEquals(2, samples.size(), samples.toString());
        assertEquals(3_000_000_000.0, valueOf(samples, "smp", "memory_bytes"));
        assertEquals(55.0, valueOf(samples, "smp", "cpu_percent"));
    }

    @Test
    void aContainerWithNoCpuReadingYetGetsNoCpuSampleRatherThanAZero() {
        final List<MetricSample> samples =
                Sampler.byService(List.of(new Sampler.Reading("limbo", 500_000_000L, OptionalDouble.empty())), AT);

        assertEquals(
                List.of("memory_bytes"),
                samples.stream().map(sample -> sample.metric().key()).toList());
    }

    @Test
    void nothingRunningIsNoSamples() {
        assertTrue(Sampler.byService(List.of(), AT).isEmpty());
    }

    private static double valueOf(final List<MetricSample> samples, final String subject, final String metric) {
        return samples.stream()
                .filter(sample -> sample.subject().equals(subject)
                        && sample.metric().key().equals(metric))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + metric + " for " + subject))
                .value();
    }
}
