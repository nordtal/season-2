package eu.nordtal.s2.hungergames.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import eu.nordtal.s2.hungergames.config.DatabaseSpec;

/**
 * Builds this plugin's own HikariCP pool, the same way {@code network-control}'s {@code AccessPool}
 * builds its own rather than using {@code AccessDirectory.open(String, String, String)}'s small
 * fixed pool - see that class for the full reasoning on why a plugin needing its own tuning still
 * builds this by hand.
 */
public final class HungerGamesPool {

    private HungerGamesPool() {
    }

    public static HikariDataSource open(final DatabaseSpec config) {
        final HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.username());
        hikari.setPassword(config.password());
        hikari.setPoolName("hunger-games");
        hikari.setMaximumPoolSize(config.maximumPoolSize());
        hikari.setConnectionTimeout(config.queryTimeoutSeconds() * 1000L);
        // Without this, HikariCP asks java.sql.DriverManager for a driver instead of loading the
        // class itself - and DriverManager's automatic ServiceLoader discovery only sees drivers
        // visible to whichever classloader happened to trigger its static init first, which on a
        // Paper server is not this plugin's own isolated PluginClassLoader. The driver is shaded
        // in correctly (META-INF/services/java.sql.Driver lists org.postgresql.Driver, verified by
        // hand in the built jar) but is never found without this - confirmed 2026-08-31 with
        // runServer against no running PostgreSQL at all: "No suitable driver" is thrown before any
        // connection attempt, which is the classloader problem, not a connectivity one.
        hikari.setDriverClassName("org.postgresql.Driver");

        // Bounds a query that is already running, not just connection acquisition. Without it a
        // database that accepts a connection and then hangs is not caught by connectionTimeout at
        // all - the same pairing network-control's AccessPool uses on the login path.
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(config.queryTimeoutSeconds()));

        return new HikariDataSource(hikari);
    }
}
