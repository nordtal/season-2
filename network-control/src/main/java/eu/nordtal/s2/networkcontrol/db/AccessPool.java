package eu.nordtal.s2.networkcontrol.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import eu.nordtal.s2.networkcontrol.config.DatabaseSpec;

/**
 * Builds the proxy's own HikariCP pool.
 * <p>
 * Not {@code AccessDirectory.open(String, String, String)}: that factory gives a fixed pool with no
 * way to tune {@code connectionTimeout} or the driver's {@code socketTimeout}, and the login path
 * needs both bounded. The proxy owns and closes this pool itself, because a pool handed to
 * {@code AccessDirectory.using(DataSource)} is one {@code close()} treats as borrowed.
 * </p>
 */
public final class AccessPool {

    private AccessPool() {
    }

    public static HikariDataSource open(final DatabaseSpec config) {
        final HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.username());
        hikari.setPassword(config.password());
        hikari.setPoolName("network-control-access");
        hikari.setMaximumPoolSize(config.maximumPoolSize());
        hikari.setConnectionTimeout(config.queryTimeoutSeconds() * 1000L);
        // Without this, HikariCP asks java.sql.DriverManager, whose ServiceLoader discovery only
        // sees drivers visible to whichever classloader triggered its static init first - not
        // necessarily this plugin's own isolated one.
        hikari.setDriverClassName("org.postgresql.Driver");

        // Bounds a query that is already running: connectionTimeout alone does not catch a
        // database that accepts the connection and then hangs.
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(config.queryTimeoutSeconds()));

        return new HikariDataSource(hikari);
    }
}
