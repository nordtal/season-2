package eu.nordtal.jcore.persistence.sql;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure unit tests for {@link DatabaseConfig}. These need no database.
 */
class DatabaseConfigTest {

    @Test
    void appliesHikariDefaultsAndDisablesSqlLoggingByDefault() {
        final DatabaseConfig config = DatabaseConfig.of("jdbc:postgresql://localhost:5432/db", "user", "secret");

        assertEquals(DatabaseConfig.DEFAULT_POOL_NAME, config.poolName());
        assertEquals(DatabaseConfig.DEFAULT_MAXIMUM_POOL_SIZE, config.maximumPoolSize());
        assertEquals(DatabaseConfig.DEFAULT_MAXIMUM_POOL_SIZE, config.minimumIdle());
        assertEquals(DatabaseConfig.DEFAULT_CONNECTION_TIMEOUT, config.connectionTimeout());
        assertEquals(DatabaseConfig.DEFAULT_IDLE_TIMEOUT, config.idleTimeout());
        assertEquals(DatabaseConfig.DEFAULT_MAX_LIFETIME, config.maxLifetime());
        assertFalse(config.logSql());
    }

    @Test
    void builderOverridesAreKept() {
        final DatabaseConfig config = DatabaseConfig.builder("jdbc:postgresql://db:5432/x")
                .username("u")
                .password("p")
                .poolName("payments")
                .maximumPoolSize(4)
                .minimumIdle(1)
                .connectionTimeout(Duration.ofSeconds(5))
                .logSql(true)
                .build();

        assertEquals("payments", config.poolName());
        assertEquals(4, config.maximumPoolSize());
        assertEquals(1, config.minimumIdle());
        assertEquals(Duration.ofSeconds(5), config.connectionTimeout());
        assertEquals(true, config.logSql());
    }

    @Test
    void rejectsNonJdbcUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseConfig.of("postgresql://localhost:5432/db", "u", "p"));
    }

    @Test
    void rejectsIdleGreaterThanMaximum() {
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseConfig.builder("jdbc:postgresql://localhost/db").maximumPoolSize(2).minimumIdle(3).build());
    }

    @Test
    void rejectsZeroPoolSize() {
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseConfig.builder("jdbc:postgresql://localhost/db").maximumPoolSize(0).build());
    }
}
