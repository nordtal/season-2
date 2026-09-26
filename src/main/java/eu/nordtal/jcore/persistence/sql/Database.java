package eu.nordtal.jcore.persistence.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Owns one HikariCP connection pool and the single {@link Jdbi} instance built on top of it.
 * <p>
 * This is the entry point of {@code eu.nordtal.jcore.persistence.sql}. Create <b>one</b> instance
 * per application per database, hand the {@link #jdbi()} around, and {@link #close()} it on shutdown.
 * Creating one instance per entity - as the pre-2.0 Hibernate code did - would open one pool per
 * entity against the same database and is exactly what this class exists to prevent.
 * </p>
 * <p>
 * The SQL dialect is implied by the JDBC URL in the {@link DatabaseConfig}; this class names no
 * specific database. It does install JDBI's {@link PostgresPlugin}, which is inert unless the
 * connection actually is PostgreSQL, and {@link SqlObjectPlugin}, which enables the declarative
 * DAO interfaces that replace the old string-keyed {@code EntityRepository}.
 * </p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * try (Database database = Database.create(
 *         DatabaseConfig.of("jdbc:postgresql://localhost:5432/nordtal", "user", "secret"))) {
 *     database.migrate();
 *     PaymentDao dao = database.jdbi().onDemand(PaymentDao.class);
 *     ...
 * }
 * }</pre>
 */
public final class Database implements AutoCloseable {

    /** The Flyway location scanned when {@link #migrate()} is called without arguments. */
    public static final String DEFAULT_MIGRATION_LOCATION = "classpath:db/migration";

    private final HikariDataSource dataSource;
    private final Jdbi jdbi;

    private Database(final @NotNull HikariDataSource dataSource, final @NotNull Jdbi jdbi) {
        this.dataSource = dataSource;
        this.jdbi = jdbi;
    }

    /**
     * Opens the connection pool and builds the {@link Jdbi} instance.
     * <p>
     * HikariCP's default {@code initializationFailTimeout} makes this <b>fail fast</b>: an
     * unreachable host, a wrong database name or wrong credentials throw here, at startup, rather
     * than on the first query. Verified against PostgreSQL on 2026-08-30.
     * </p>
     *
     * @param config the connection and pool settings
     * @return a new, open {@link Database}
     * @throws NullPointerException if {@code config} is {@code null}
     * @throws com.zaxxer.hikari.pool.HikariPool.PoolInitializationException if the initial
     *         connection cannot be established
     * @throws RuntimeException if HikariCP rejects the configuration
     */
    public static @NotNull Database create(final @NotNull DatabaseConfig config) {
        Objects.requireNonNull(config, "config");

        final HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.jdbcUrl());
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());
        hikariConfig.setPoolName(config.poolName());
        hikariConfig.setMaximumPoolSize(config.maximumPoolSize());
        hikariConfig.setMinimumIdle(config.minimumIdle());
        hikariConfig.setConnectionTimeout(config.connectionTimeout().toMillis());
        hikariConfig.setIdleTimeout(config.idleTimeout().toMillis());
        hikariConfig.setMaxLifetime(config.maxLifetime().toMillis());

        final HikariDataSource dataSource = new HikariDataSource(hikariConfig);

        try {
            final Jdbi jdbi =
                    Jdbi.create(dataSource).installPlugin(new SqlObjectPlugin()).installPlugin(new PostgresPlugin());

            if (config.logSql()) {
                jdbi.setSqlLogger(new Slf4jSqlLogger());
            }

            return new Database(dataSource, jdbi);
        } catch (final RuntimeException exception) {
            // Do not leak the pool if plugin installation blows up.
            dataSource.close();
            throw exception;
        }
    }

    /**
     * @return the shared {@link Jdbi} instance; use {@code onDemand}, {@code withHandle} or
     *         {@code inTransaction} on it
     */
    public @NotNull Jdbi jdbi() {
        return jdbi;
    }

    /**
     * @return the underlying pooled {@link DataSource}, for the rare consumer that needs raw JDBC
     *         or a third-party tool that takes a {@code DataSource}
     */
    public @NotNull DataSource dataSource() {
        return dataSource;
    }

    /**
     * Runs the pending Flyway migrations found in {@link #DEFAULT_MIGRATION_LOCATION}.
     *
     * @return the number of migrations that were applied
     * @throws org.flywaydb.core.api.FlywayException if a migration fails or the schema history is inconsistent
     */
    public int migrate() {
        return migrate(DEFAULT_MIGRATION_LOCATION);
    }

    /**
     * Runs the pending Flyway migrations found in the given locations.
     * <p>
     * Flyway is resolved against this class's own class loader, so migrations bundled inside a
     * shaded plugin jar are found regardless of the thread's context class loader.
     * </p>
     *
     * @param locations the Flyway locations, e.g. {@code classpath:db/migration} or {@code filesystem:/opt/sql}
     * @return the number of migrations that were applied
     * @throws IllegalArgumentException if {@code locations} is empty
     * @throws org.flywaydb.core.api.FlywayException if a migration fails or the schema history is inconsistent
     */
    public int migrate(final @NotNull String @NotNull ... locations) {
        Objects.requireNonNull(locations, "locations");
        if (locations.length == 0) {
            throw new IllegalArgumentException("at least one migration location is required");
        }

        final Flyway flyway = Flyway.configure(Database.class.getClassLoader())
                .dataSource(dataSource)
                .locations(locations)
                .load();

        return flyway.migrate().migrationsExecuted;
    }

    /**
     * Closes the connection pool. Idempotent. After this the {@link #jdbi()} instance is unusable.
     */
    @Override
    public void close() {
        dataSource.close();
    }
}
