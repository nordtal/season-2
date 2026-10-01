package eu.nordtal.s2.steward.metric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.database.metric.MetricDirectory;
import eu.nordtal.s2.settings.DatabaseSpec;
import eu.nordtal.s2.steward.docker.Docker;
import eu.nordtal.s2.steward.docker.DockerSocket;
import eu.nordtal.s2.steward.host.HostMetrics;
import eu.nordtal.s2.steward.schema.Schema;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The sampler, end to end: the real daemon on one side, a throwaway PostgreSQL on the other.
 *
 * The live database stays untouched, since a migration it does not know would stop the deployed bot.
 */
class SamplerIntegrationTest {

    private static final String PROJECT = "nordtal-s2";

    private static TestDatabase postgres;
    private static Database database;

    @BeforeAll
    static void startDatabase() {
        postgres = TestDatabase.empty();

        // Steward's own migration path, not a hand-rolled Flyway call, as a migration arrives in production.
        database = Schema.open(new DatabaseSpec() {
            @Override
            public String jdbcUrl() {
                return postgres.jdbcUrl();
            }

            @Override
            public String username() {
                return postgres.username();
            }

            @Override
            public String password() {
                return postgres.password();
            }
        });
        Schema.migrate(database);
    }

    @AfterAll
    static void stopDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void oneRoundWritesTheHostsNumbersAndOneRowPerRunningContainer() {
        final DockerSocket socket = new DockerSocket();
        assumeTrue(socket.isReachable(), "no docker socket - skipping");
        final Docker docker = new Docker(socket);
        final MetricDirectory metrics = MetricDirectory.using(database.dataSource());

        final Instant at = Instant.now();
        final int written;
        try (Sampler sampler = new Sampler(docker, new HostMetrics(), metrics, PROJECT, Clock.systemUTC())) {
            // Twice: the first round has no previous CPU reading to subtract from, so it carries no cpu_percent.
            sampler.tick(at.minusSeconds(30));
            written = sampler.tick(at);
        }
        assertTrue(written > 0, "a round wrote nothing at all");

        assertFalse(
                metrics.range("host", "memory_used_bytes", at.minusSeconds(60), at.plusSeconds(60))
                        .isEmpty(),
                "the host's memory never arrived in the table");

        final Set<String> services = docker.containers(PROJECT).stream()
                .filter(container -> container.service() != null && container.isRunning())
                .map(Docker.Container::service)
                .collect(Collectors.toSet());
        assumeTrue(!services.isEmpty(), "nothing of the stack is running - skipping");

        for (final String service : services) {
            assertFalse(
                    metrics.range(service, "memory_bytes", at.minusSeconds(60), at.plusSeconds(60))
                            .isEmpty(),
                    service + " is running but wrote no memory sample");
        }
    }

    @Test
    void theSameInstantSampledTwiceIsOneReadingNotTwo() {
        final DockerSocket socket = new DockerSocket();
        assumeTrue(socket.isReachable(), "no docker socket - skipping");
        final MetricDirectory metrics = MetricDirectory.using(database.dataSource());

        // Keyed by subject, metric, resolution and time, so a second round must land on the first, not beside it.
        final Instant at = Instant.parse("2026-09-13T00:00:00Z");
        try (Sampler sampler =
                new Sampler(new Docker(socket), new HostMetrics(), metrics, PROJECT, Clock.systemUTC())) {
            sampler.tick(at);
            sampler.tick(at);
        }

        // Exactly one, not "at most one": <= 1 also passes for a sampler that has quietly stopped recording.
        assertEquals(
                1,
                metrics.range("host", "memory_used_bytes", at.minusSeconds(1), at.plusSeconds(1))
                        .size(),
                "one instant, sampled twice, is one row of that series - no more and no fewer");
    }
}
