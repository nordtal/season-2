package eu.nordtal.season.settings;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;

/** The one builder of a connection pool, for every process that opens one from its {@code database} group. */
public final class DatabasePool {

    private DatabasePool() {}

    /**
     * Refuses a {@code database} group no pool can be opened from.
     *
     * @throws IllegalArgumentException naming the first value that is wrong
     */
    public static void check(final DatabaseSpec database) {
        Checks.requireText("jdbc-url", database.jdbcUrl());
        Checks.requireText("username", database.username());
        if (!database.jdbcUrl().startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException(
                    "jdbc-url must be a PostgreSQL URL (jdbc:postgresql://host:port/database)");
        }
        Checks.requirePositive("maximum-pool-size", database.maximumPoolSize());
        Checks.requirePositive("query-timeout-seconds", database.queryTimeoutSeconds());
    }

    /** Opens a pool named {@code name}, whose timeout bounds both a connection wait and a query already running. */
    public static HikariDataSource open(final DatabaseSpec database, final String name) {
        return open(database, name, Duration.ofSeconds(database.queryTimeoutSeconds()));
    }

    /**
     * Opens a pool named {@code name} whose running query is bounded by {@code queryBound}, for a longer one.
     *
     * The connection wait stays at {@code query-timeout-seconds}.
     */
    public static HikariDataSource open(final DatabaseSpec database, final String name, final Duration queryBound) {
        if (queryBound.isNegative() || queryBound.isZero()) {
            throw new IllegalArgumentException("the query bound must be positive, was " + queryBound);
        }
        final HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(database.jdbcUrl());
        hikari.setUsername(database.username());
        hikari.setPassword(database.password());
        hikari.setPoolName(name);
        hikari.setMaximumPoolSize(database.maximumPoolSize());
        hikari.setConnectionTimeout(database.queryTimeoutSeconds() * 1000L);
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(Math.max(1, queryBound.toSeconds())));
        // DriverManager's ServiceLoader misses a driver that only a plugin's own classloader can see.
        hikari.setDriverClassName("org.postgresql.Driver");
        return new HikariDataSource(hikari);
    }
}
