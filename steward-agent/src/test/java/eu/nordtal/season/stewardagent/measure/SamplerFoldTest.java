package eu.nordtal.season.stewardagent.measure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.internalapi.agent.AgentWire;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

/** The step between "what each container answered" and "what the chart is keyed by". */
class SamplerFoldTest {

    private static final Instant AT = Instant.parse("2026-09-13T04:45:00Z");

    @Test
    void oneContainerPerServiceIsOneReadingUnchanged() {
        final Map<String, AgentWire.Reading> services = Sampler.byService(
                List.of(
                        new Sampler.Reading("smp", 2_000_000_000L, 0, OptionalDouble.of(42.5)),
                        new Sampler.Reading("postgres", 300_000_000L, 0, OptionalDouble.of(1.5))),
                AT);

        assertEquals(2, services.size());
        assertEquals(2_000_000_000L, services.get("smp").memoryBytes());
        assertEquals(42.5, services.get("smp").cpuPercent());
        assertEquals(300_000_000L, services.get("postgres").memoryBytes());
        assertEquals(AT, services.get("smp").at());
    }

    @Test
    void twoContainersOfOneServiceAreOneReadingNotOneOfThemSilentlyKept() {
        final Map<String, AgentWire.Reading> services = Sampler.byService(
                List.of(
                        new Sampler.Reading("smp", 2_000_000_000L, 0, OptionalDouble.of(40.0)),
                        new Sampler.Reading("smp", 1_000_000_000L, 0, OptionalDouble.of(15.0))),
                AT);

        assertEquals(1, services.size(), services.toString());
        assertEquals(3_000_000_000L, services.get("smp").memoryBytes());
        assertEquals(55.0, services.get("smp").cpuPercent());
    }

    @Test
    void aContainerWithNoCpuReadingYetHasNoCpuRatherThanAZero() {
        final Map<String, AgentWire.Reading> services =
                Sampler.byService(List.of(new Sampler.Reading("limbo", 500_000_000L, 0, OptionalDouble.empty())), AT);

        assertNull(services.get("limbo").cpuPercent());
    }

    @Test
    void nothingRunningIsNoReadings() {
        assertTrue(Sampler.byService(List.of(), AT).isEmpty());
    }
}
