package eu.nordtal.s2.common.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.s2.common.access.AccessSchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A run handed from one steward-worker to the next, against a real PostgreSQL.
 *
 * The row is the whole handover: the worker that places a newer jar puts the request back into the inbox and exits,
 * and the one that starts has nothing but the row to go on. Whether it is claimable, invisible to the countdown and
 * safe from a restart's settleOrphans is a property of SQL, which is why it is asserted here and not on a fake.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UpdateHandoverIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static PGSimpleDataSource dataSource;

    private UpdateDirectory updates;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed update tests");

        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("access")
                .withUsername("access")
                .withPassword("access");
        postgres.start();

        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        AccessSchema.migrate(dataSource);
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
            postgres = null;
        }
        dataSource = null;
    }

    @BeforeEach
    void freshInbox() {
        execute("TRUNCATE TABLE service_hold, update_request RESTART IDENTITY");
        updates = UpdateDirectory.using(dataSource);
    }

    @Test
    void aHandedOverRunIsClaimedAgainWithItsReportAndStaysOpenInBetween() {
        // The worker hands a run to the version it just placed and exits; the new one must find it and its note.
        final UpdateRequest run = updates.submit(UpdateKind.UPDATE, UpdateSource.CONSOLE, "a", Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());

        assertTrue(updates.handOver(run.id(), "{\"stage\":\"RESOLVING\"}"));

        final UpdateRequest waiting = updates.find(run.id()).orElseThrow();
        assertEquals(UpdateStatus.PENDING, waiting.status());
        assertNull(waiting.started(), "nobody is running it until the next worker claims it");
        assertEquals(run.id(), updates.open().orElseThrow().id(), "and no second run can slip in meanwhile");
        assertTrue(updates.countingDown().isEmpty(), "and nobody is counted down to a handover");
        assertEquals(0, updates.settleOrphans("orphaned"), "a restart must not settle a run that was handed over");

        final UpdateRequest again = updates.claimNext().orElseThrow();
        assertEquals(run.id(), again.id());
        assertEquals("{\"stage\":\"RESOLVING\"}", again.result());
    }

    @Test
    void onlyARunningRunCanBeHandedOver() {
        // A run settled a moment before - by a restart's settleOrphans, say - must not come back to life as PENDING.
        final UpdateRequest run = updates.submit(UpdateKind.UPDATE, UpdateSource.CONSOLE, "a", Duration.ZERO);
        assertTrue(updates.claimNext().isPresent());
        updates.finish(run.id(), UpdateStatus.FAILED, "{}");

        assertFalse(updates.handOver(run.id(), "{}"));
        assertEquals(UpdateStatus.FAILED, updates.find(run.id()).orElseThrow().status());
    }

    private static void execute(final String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (final SQLException failure) {
            throw new IllegalStateException(sql, failure);
        }
    }
}
