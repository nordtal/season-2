package eu.nordtal.s2.steward.worker.metric;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.s2.common.metric.MetricDirectory;
import eu.nordtal.s2.steward.worker.config.DatabaseSpec;
import eu.nordtal.s2.steward.worker.docker.Docker;
import eu.nordtal.s2.steward.worker.docker.DockerSocket;
import eu.nordtal.s2.steward.worker.host.HostMetrics;
import eu.nordtal.s2.steward.worker.schema.Schema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The sampler, end to end: the real daemon on one side, a real PostgreSQL on the other.
 *
 * <h2>Why a throwaway database and not the one on this host</h2>
 * The live database belongs to a deployment running the released jar. Applying this branch's
 * migration to it would leave a version in its history the deployed bot does not know, and the bot
 * calls {@code validate()} and refuses to start against exactly that. So the migration is proved
 * where breaking it costs nothing, and the live one is left alone until the cutover.
 */
class SamplerIntegrationTest {

    private static final String PROJECT = "nordtal-s2";

    private static PostgreSQLContainer<?> postgres;
    private static Database database;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "no docker daemon - skipping");
        postgres = new PostgreSQLContainer<>("postgres:17-alpine");
        postgres.start();

        // The worker's own migration path, not a hand-rolled Flyway call: what is under test
        // includes V15 arriving the way it will arrive in production.
        database = Schema.open(new DatabaseSpec() {
            @Override
            public String jdbcUrl() {
                return postgres.getJdbcUrl();
            }

            @Override
            public String username() {
                return postgres.getUsername();
            }

            @Override
            public String password() {
                return postgres.getPassword();
            }
        });
        Schema.migrate(database);
    }

    @AfterAll
    static void stopDatabase() {
        if (database != null) {
            database.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @DisplayName("one round writes the host's numbers and one row per running container")
    void oneRoundLandsInTheDatabase() {
        final DockerSocket socket = new DockerSocket();
        assumeTrue(socket.isReachable(), "no docker socket - skipping");
        final Docker docker = new Docker(socket);
        final MetricDirectory metrics = MetricDirectory.using(database.dataSource());

        final Instant at = Instant.now();
        final int written;
        try (Sampler sampler = new Sampler(docker, new HostMetrics(), metrics, PROJECT)) {
            // Twice: the first round has no previous CPU reading to subtract from - for the host and
            // for every container - so it is the round that legitimately carries no cpu_percent.
            sampler.tick(at.minusSeconds(30));
            written = sampler.tick(at);
        }
        assertTrue(written > 0, "a round wrote nothing at all");

        assertFalse(metrics.range("host", "memory_used_bytes",
                        at.minusSeconds(60), at.plusSeconds(60)).isEmpty(),
                "the host's memory never arrived in the table");

        final Set<String> services = docker.containers(PROJECT).stream()
                .filter(container -> container.service() != null && container.isRunning())
                .map(Docker.Container::service)
                .collect(Collectors.toSet());
        assumeTrue(!services.isEmpty(), "nothing of the stack is running - skipping");

        for (final String service : services) {
            assertFalse(metrics.range(service, "memory_bytes",
                            at.minusSeconds(60), at.plusSeconds(60)).isEmpty(),
                    service + " is running but wrote no memory sample");
        }
    }

    @Test
    @DisplayName("the same instant sampled twice is one reading, not two")
    void samplingTwiceIsNotTwiceTheRows() {
        final DockerSocket socket = new DockerSocket();
        assumeTrue(socket.isReachable(), "no docker socket - skipping");
        final MetricDirectory metrics = MetricDirectory.using(database.dataSource());

        // The same instant twice is what a restart at an unlucky moment looks like. The table is
        // keyed by subject, metric, resolution and time, so the second round has to land on the
        // first rather than beside it - two values for one moment is a chart nobody can read.
        final Instant at = Instant.parse("2026-09-13T00:00:00Z");
        try (Sampler sampler = new Sampler(new Docker(socket), new HostMetrics(), metrics, PROJECT)) {
            sampler.tick(at);
            sampler.tick(at);
        }

        // Exactly one, not "at most one": <= 1 is also what two rounds that wrote nothing at all
        // look like, and a sampler that has quietly stopped recording passes that assertion every
        // time. The row has to be there, and there has to be one of it.
        assertEquals(1, metrics.range("host", "memory_used_bytes",
                        at.minusSeconds(1), at.plusSeconds(1)).size(),
                "one instant, sampled twice, is one row of that series - no more and no fewer");
    }
}
