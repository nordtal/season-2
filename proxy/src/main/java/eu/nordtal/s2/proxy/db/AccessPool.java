package eu.nordtal.s2.proxy.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import eu.nordtal.s2.proxy.config.DatabaseSpec;

/**
 * Builds the proxy's own HikariCP pool.
 *
 * Not {@code AccessDirectory.open(String, String, String)}: that factory gives a fixed pool with no
 * way to tune {@code connectionTimeout} or the driver's {@code socketTimeout}, and the login path
 * needs both bounded. The proxy owns and closes this pool itself, because a pool handed to
 * {@code AccessDirectory.using(DataSource)} is one {@code close()} treats as borrowed.
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
        // Without this, ServiceLoader discovery may miss drivers not visible to this plugin's own classloader.
        hikari.setDriverClassName("org.postgresql.Driver");

        // Bounds a query already running: connectionTimeout alone misses a database that hangs after accepting.
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(config.queryTimeoutSeconds()));

        return new HikariDataSource(hikari);
    }
}
