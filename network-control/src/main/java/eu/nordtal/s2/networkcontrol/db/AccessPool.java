package eu.nordtal.s2.networkcontrol.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import eu.nordtal.s2.networkcontrol.config.DatabaseSpec;

/**
 * Builds the proxy's own HikariCP pool.
 * <p>
 * Not {@code AccessDirectory.open(String, String, String)}: that factory hands out a small, fixed
 * pool with no way to tune {@code connectionTimeout} or the PostgreSQL driver's own
 * {@code socketTimeout}, and both matter on the login path - "one query and a short timeout"
 * (docs/access-system.md) has to be an actual property of the connection, not a hope.
 * {@code AccessDirectory.using(DataSource)} accepts any pool, so this builds one with both
 * timeouts driven by {@link DatabaseSpec#queryTimeoutSeconds()} and hands it in; the proxy owns
 * and closes this pool itself, since a pool handed to {@code using(...)} is one
 * {@code AccessDirectory.close()} treats as borrowed and never touches.
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

        // Bounds a query that is already running, not just connection acquisition - without this,
        // a database that accepts a connection and then hangs on the query itself would not be
        // caught by connectionTimeout at all, and a login would wait on it far longer than
        // "short".
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(config.queryTimeoutSeconds()));

        return new HikariDataSource(hikari);
    }
}
