package eu.nordtal.s2.proxy.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import eu.nordtal.s2.proxy.config.DatabaseSpec;

/**
 * Builds the proxy's own HikariCP pool, with both timeouts bounded for the login path.
 *
 * The proxy owns and closes it: a pool handed to {@code AccessDirectory.using} is treated as borrowed.
 */
public final class AccessPool {

    private AccessPool() {}

    public static HikariDataSource open(final DatabaseSpec config) {
        final HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.username());
        hikari.setPassword(config.password());
        hikari.setPoolName("proxy-access");
        hikari.setMaximumPoolSize(config.maximumPoolSize());
        hikari.setConnectionTimeout(config.queryTimeoutSeconds() * 1000L);
        // Without this, ServiceLoader may miss a driver this plugin's classloader cannot see.
        hikari.setDriverClassName("org.postgresql.Driver");

        // Bounds a running query: connectionTimeout alone misses a database that hangs after accepting.
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(config.queryTimeoutSeconds()));

        return new HikariDataSource(hikari);
    }
}
