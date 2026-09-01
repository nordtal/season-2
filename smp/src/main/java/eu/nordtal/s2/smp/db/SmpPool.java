package eu.nordtal.s2.smp.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import eu.nordtal.s2.smp.config.DatabaseSpec;

/**
 * The SMP's connection pool, built here rather than taken from jcore's {@code Database} so that the
 * pool name, the size and both timeouts are this module's own - the same reasoning, and the same
 * shape, as {@code hunger-games}' pool.
 *
 * <p>This plugin never migrates anything. Exactly one process owns the schema and it is the Discord
 * bot (docs/architecture.md#schema-ownership), which is also why {@code flyway-core} is excluded
 * from this module's dependencies.
 */
public final class SmpPool {

    private SmpPool() {
    }

    public static HikariDataSource open(final DatabaseSpec config) {
        final HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.username());
        hikari.setPassword(config.password());
        hikari.setPoolName("smp");
        hikari.setMaximumPoolSize(config.maximumPoolSize());
        hikari.setConnectionTimeout(config.queryTimeoutSeconds() * 1000L);
        hikari.setDriverClassName("org.postgresql.Driver");
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(config.queryTimeoutSeconds()));
        return new HikariDataSource(hikari);
    }
}
