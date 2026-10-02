package eu.nordtal.s2.stewardagent.measure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.stewardagent.docker.Docker;
import eu.nordtal.s2.stewardagent.docker.DockerSocket;
import eu.nordtal.s2.stewardagent.docker.FakeDaemon;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What steward collects: the rounds after the last one it stored, an hour of them at most, oldest first. */
class SamplerRoundsTest {

    private static final Instant NIGHT = Instant.parse("2026-10-02T02:00:00Z");

    @TempDir
    private Path scratch;

    @Test
    void aReaderGetsOnlyTheRoundsAfterTheLastItStoredOldestFirst() throws Exception {
        try (FakeDaemon daemon = new FakeDaemon(scratch);
                Sampler sampler = sampler(daemon)) {
            assertNull(sampler.latest(), "no round before the first tick");
            sampler.tick(NIGHT);
            sampler.tick(NIGHT.plusSeconds(30));
            sampler.tick(NIGHT.plusSeconds(60));

            assertEquals(
                    List.of(NIGHT.plusSeconds(30), NIGHT.plusSeconds(60)),
                    sampler.after(NIGHT).stream().map(AgentWire.Round::at).toList());
            assertEquals(3, sampler.after(null).size());
            assertEquals(NIGHT.plusSeconds(60), sampler.latest().at());
            assertEquals(1, sampler.latestByService().size(), "the one running smp");
        }
    }

    @Test
    void anHourOfRoundsIsKeptAndTheOldestGoFirst() throws Exception {
        try (FakeDaemon daemon = new FakeDaemon(scratch);
                Sampler sampler = sampler(daemon)) {
            for (int round = 0; round < Sampler.KEPT + 5; round++) {
                sampler.tick(NIGHT.plus(Sampler.PERIOD.multipliedBy(round)));
            }
            final List<AgentWire.Round> kept = sampler.after(null);
            assertEquals(Sampler.KEPT, kept.size());
            assertEquals(
                    NIGHT.plus(Sampler.PERIOD.multipliedBy(5)), kept.getFirst().at());
        }
    }

    private static Sampler sampler(final FakeDaemon daemon) {
        return new Sampler(
                new Docker(new DockerSocket(daemon.socket(), Duration.ofSeconds(5))),
                new HostMetrics(),
                FakeDaemon.PROJECT,
                Clock.systemUTC());
    }
}
