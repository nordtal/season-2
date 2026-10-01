package eu.nordtal.s2.discordbot;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.jcore.persistence.sql.Database;
import eu.nordtal.jcore.persistence.sql.DatabaseConfig;
import eu.nordtal.s2.database.Jdbis;
import eu.nordtal.s2.database.TestDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** The startup schema check against a real PostgreSQL, skipped when no Docker daemon is reachable. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class SchemaCheckTest {

    private static TestDatabase postgres;
    private static Database database;

    @BeforeAll
    static void startDatabase() {
        postgres = TestDatabase.empty();

        database = Database.create(DatabaseConfig.of(postgres.jdbcUrl(), postgres.username(), postgres.password()));
        database.jdbi().installPlugin(Jdbis.ids());
    }

    @AfterAll
    static void stopDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void anUnmigratedDatabaseIsRefusedAndTheMessageNamesTheCommandThatFixesIt() {
        // Deliberately first: the database is not migrated yet.
        final IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> SchemaCheck.validate(database.dataSource()));

        assertTrue(refused.getMessage().contains("steward migrate"), refused.getMessage());
        assertTrue(refused.getMessage().contains("does not apply migrations any more"), refused.getMessage());
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void aMigratedDatabasePasses() {
        // The fixture migrates as steward does, placeholders and all.
        assertDoesNotThrow(() -> SchemaCheck.validate(TestDatabase.fresh().dataSource()));
    }
}
