package eu.nordtal.s2.hungergames.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import eu.nordtal.s2.hungergames.config.DatabaseSpec;

/**
 * Builds this plugin's own HikariCP pool, tuned from {@link DatabaseSpec}, as {@code proxy}'s {@code AccessPool} does.
 */
public final class HungerGamesPool {

    private HungerGamesPool() {}

    public static HikariDataSource open(final DatabaseSpec config) {
        final HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.username());
        hikari.setPassword(config.password());
        hikari.setPoolName("hunger-games");
        hikari.setMaximumPoolSize(config.maximumPoolSize());
        hikari.setConnectionTimeout(config.queryTimeoutSeconds() * 1000L);
        // DriverManager cannot see a driver in this plugin's classloader, so "No suitable driver" without it.
        hikari.setDriverClassName("org.postgresql.Driver");

        // Bounds a query already running, not only connection acquisition.
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(config.queryTimeoutSeconds()));

        return new HikariDataSource(hikari);
    }
}
