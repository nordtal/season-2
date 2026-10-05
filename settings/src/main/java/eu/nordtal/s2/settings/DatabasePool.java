package eu.nordtal.s2.settings;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** The connection pool a Minecraft process opens from its {@code database} group. */
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
        final HikariConfig hikari = config(database, name);
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(database.queryTimeoutSeconds()));
        return new HikariDataSource(hikari);
    }

    /** Opens a pool named {@code name} whose timeout bounds only a connection wait, for a service that migrates. */
    public static HikariDataSource openUnbounded(final DatabaseSpec database, final String name) {
        return new HikariDataSource(config(database, name));
    }

    private static HikariConfig config(final DatabaseSpec database, final String name) {
        final HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(database.jdbcUrl());
        hikari.setUsername(database.username());
        hikari.setPassword(database.password());
        hikari.setPoolName(name);
        hikari.setMaximumPoolSize(database.maximumPoolSize());
        hikari.setConnectionTimeout(database.queryTimeoutSeconds() * 1000L);
        // DriverManager's ServiceLoader misses a driver that only a plugin's own classloader can see.
        hikari.setDriverClassName("org.postgresql.Driver");
        return hikari;
    }
}
