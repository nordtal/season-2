package eu.nordtal.s2.smp.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import eu.nordtal.s2.smp.config.DatabaseSpec;

/** The SMP's own connection pool, which never migrates anything. */
public final class SmpPool {

    private SmpPool() {}

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
