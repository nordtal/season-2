package eu.nordtal.s2.limbo.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import eu.nordtal.s2.limbo.config.DatabaseSpec;

/**
 * Builds this plugin's own HikariCP pool, the same way {@code proxy}'s {@code AccessPool}
 * builds its own rather than using {@code AccessDirectory.open(String, String, String)}'s small
 * fixed pool - see that class for the full reasoning on why a plugin needing its own tuning still
 * builds this by hand.
 */
public final class LimboPool {

    private LimboPool() {}

    public static HikariDataSource open(final DatabaseSpec config) {
        final HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.username());
        hikari.setPassword(config.password());
        hikari.setPoolName("limbo");
        hikari.setMaximumPoolSize(config.maximumPoolSize());
        hikari.setConnectionTimeout(config.queryTimeoutSeconds() * 1000L);
        // Without this HikariCP asks DriverManager, whose ServiceLoader discovery misses this plugin's own classloader.
        hikari.setDriverClassName("org.postgresql.Driver");

        // Bounds a query already running, not just connection acquisition; the same pairing proxy's AccessPool uses.
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(config.queryTimeoutSeconds()));

        return new HikariDataSource(hikari);
    }
}
