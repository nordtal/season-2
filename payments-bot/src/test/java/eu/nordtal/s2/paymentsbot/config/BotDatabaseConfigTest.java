package eu.nordtal.s2.paymentsbot.config;

import eu.nordtal.jcore.persistence.sql.DatabaseConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BotDatabaseConfigTest {

    /**
     * The environment cannot be mutated from inside the JVM, so only the file path is exercised
     * here. The assumption keeps this from passing for the wrong reason if the suite ever runs
     * with the overrides set.
     */
    @Test
    void fallsBackToTheFileWhenNoEnvironmentOverrideIsSet() {
        assumeTrue(System.getenv(BotDatabaseConfig.POSTGRES_URL_ENV) == null
                        && System.getenv(BotDatabaseConfig.POSTGRES_USER_ENV) == null
                        && System.getenv(BotDatabaseConfig.POSTGRES_PASSWORD_ENV) == null,
                "POSTGRES_* is set in this environment; the file-fallback case cannot be observed");

        final BotDatabaseConfig config = new BotDatabaseConfig();
        config.setJdbcUrl("jdbc:postgresql://db:5432/from-file");
        config.setUsername("file-user");
        config.setPassword("file-password");

        final DatabaseConfig databaseConfig = config.toDatabaseConfig();

        assertEquals("jdbc:postgresql://db:5432/from-file", databaseConfig.jdbcUrl());
        assertEquals("file-user", databaseConfig.username());
        assertEquals("file-password", databaseConfig.password());
        assertEquals("payments-bot", databaseConfig.poolName());
    }
}
