package eu.nordtal.s2.discordbot;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.jcore.persistence.sql.DatabaseConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * What the bot does at startup now that it does not migrate: check, and refuse if the answer is no.
 *
 * Against a real PostgreSQL, because the thing being tested is the state of a database. Driven by hand from
 * {@link BeforeAll} rather than through {@code @Testcontainers} for the same reason as every other integration test
 * here - that extension is built against JUnit 5 and this repo is on the JUnit 6 BOM - and it skips itself when no
 * Docker daemon is reachable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class SchemaCheckTest {

    private static PostgreSQLContainer<?> postgres;
    private static Database database;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "No Docker daemon reachable - skipping the PostgreSQL-backed tests");

        postgres = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("access")
                .withUsername("access")
                .withPassword("access");
        postgres.start();

        database = Database.create(
                DatabaseConfig.of(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
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
    @org.junit.jupiter.api.Order(1)
    void anUnmigratedDatabaseIsRefusedAndTheMessageNamesTheCommandThatFixesIt() {
        // Deliberately first: runs before anything migrates the container, the state before steward-worker.
        final IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> SchemaCheck.validate(database.dataSource()));

        assertTrue(refused.getMessage().contains("steward-worker migrate"), refused.getMessage());
        assertTrue(refused.getMessage().contains("does not apply migrations any more"), refused.getMessage());
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void aMigratedDatabasePasses() {
        database.migrate();

        assertDoesNotThrow(() -> SchemaCheck.validate(database.dataSource()));
    }
}
