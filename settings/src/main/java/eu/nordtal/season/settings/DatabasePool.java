package eu.nordtal.season.settings;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;

public final class DatabasePool {

    private DatabasePool() {}

    /**
     * @throws IllegalArgumentException naming the first wrong value
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

    /** Opens a pool named {@code name}.
     * {@code query-timeout-seconds} bounds both the wait for a connection and a running query.
     */
    public static HikariDataSource open(final DatabaseSpec database, final String name) {
        return open(database, name, Duration.ofSeconds(database.queryTimeoutSeconds()));
    }

    /** Opens a pool named {@code name} that bounds a running query by {@code queryBound}. */
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
        // DriverManager's ServiceLoader cannot see a driver only a plugin's classloader sees.
        hikari.setDriverClassName("org.postgresql.Driver");
        return new HikariDataSource(hikari);
    }
}
