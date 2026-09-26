package eu.nordtal.jcore.persistence.sql;

import java.time.Duration;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable connection and pool settings for a {@link Database}.
 * <p>
 * The SQL dialect is <b>not</b> part of this configuration - it is implied by the JDBC URL.
 * A PostgreSQL URL ({@code jdbc:postgresql://host:5432/db}) is what the bundled driver and the
 * JDBI Postgres plugin are built for, but nothing here hard-codes that choice.
 * </p>
 *
 * @param jdbcUrl the JDBC URL, must start with {@code jdbc:}
 * @param username the database user, may be {@code null} when the URL carries the credentials
 * @param password the database password, may be {@code null} when the URL carries the credentials
 * @param poolName the HikariCP pool name, shows up in logs and JMX
 * @param maximumPoolSize the maximum number of connections the pool keeps open, at least {@code 1}
 * @param minimumIdle the number of connections the pool tries to keep idle, between {@code 0} and {@code maximumPoolSize}
 * @param connectionTimeout how long {@code getConnection()} waits before failing
 * @param idleTimeout how long a connection may sit idle before it is retired, {@link Duration#ZERO} disables retirement
 * @param maxLifetime the maximum age of a connection in the pool
 * @param logSql whether every executed statement is logged to SLF4J at {@code DEBUG} level
 */
public record DatabaseConfig(
        @NotNull String jdbcUrl,
        @Nullable String username,
        @Nullable String password,
        @NotNull String poolName,
        int maximumPoolSize,
        int minimumIdle,
        @NotNull Duration connectionTimeout,
        @NotNull Duration idleTimeout,
        @NotNull Duration maxLifetime,
        boolean logSql) {

    /** The pool name used when the caller does not pick one. */
    public static final String DEFAULT_POOL_NAME = "jcore-pool";

    /** HikariCP's own default maximum pool size. */
    public static final int DEFAULT_MAXIMUM_POOL_SIZE = 10;

    /** HikariCP's own default connection timeout. */
    public static final Duration DEFAULT_CONNECTION_TIMEOUT = Duration.ofSeconds(30);

    /** HikariCP's own default idle timeout. */
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(10);

    /** HikariCP's own default maximum connection lifetime. */
    public static final Duration DEFAULT_MAX_LIFETIME = Duration.ofMinutes(30);

    /**
     * Validates the configuration.
     *
     * @throws IllegalArgumentException if the URL is not a JDBC URL or the pool sizing is nonsensical
     */
    public DatabaseConfig {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Objects.requireNonNull(poolName, "poolName");
        Objects.requireNonNull(connectionTimeout, "connectionTimeout");
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        Objects.requireNonNull(maxLifetime, "maxLifetime");

        if (!jdbcUrl.startsWith("jdbc:")) {
            throw new IllegalArgumentException("jdbcUrl must start with \"jdbc:\", got: " + jdbcUrl);
        }
        if (maximumPoolSize < 1) {
            throw new IllegalArgumentException("maximumPoolSize must be at least 1, got: " + maximumPoolSize);
        }
        if (minimumIdle < 0 || minimumIdle > maximumPoolSize) {
            throw new IllegalArgumentException(
                    "minimumIdle must be between 0 and maximumPoolSize (" + maximumPoolSize + "), got: " + minimumIdle);
        }
        if (connectionTimeout.isNegative() || connectionTimeout.isZero()) {
            throw new IllegalArgumentException("connectionTimeout must be positive, got: " + connectionTimeout);
        }
        if (idleTimeout.isNegative()) {
            throw new IllegalArgumentException("idleTimeout must not be negative, got: " + idleTimeout);
        }
        if (maxLifetime.isNegative()) {
            throw new IllegalArgumentException("maxLifetime must not be negative, got: " + maxLifetime);
        }
    }

    /**
     * Starts building a configuration with HikariCP's defaults and SQL logging turned off.
     *
     * @param jdbcUrl the JDBC URL, must start with {@code jdbc:}
     * @return a new {@link Builder}
     */
    public static @NotNull Builder builder(final @NotNull String jdbcUrl) {
        return new Builder(jdbcUrl);
    }

    /**
     * Convenience shortcut for the common case: URL, user, password, everything else default.
     *
     * @param jdbcUrl the JDBC URL, must start with {@code jdbc:}
     * @param username the database user
     * @param password the database password
     * @return a configuration using HikariCP's defaults
     */
    public static @NotNull DatabaseConfig of(
            final @NotNull String jdbcUrl, final @Nullable String username, final @Nullable String password) {
        return builder(jdbcUrl).username(username).password(password).build();
    }

    /**
     * Mutable builder for {@link DatabaseConfig}. Not thread-safe; build once, share the record.
     */
    public static final class Builder {

        private final String jdbcUrl;
        private String username;
        private String password;
        private String poolName = DEFAULT_POOL_NAME;
        private int maximumPoolSize = DEFAULT_MAXIMUM_POOL_SIZE;
        private Integer minimumIdle;
        private Duration connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;
        private Duration idleTimeout = DEFAULT_IDLE_TIMEOUT;
        private Duration maxLifetime = DEFAULT_MAX_LIFETIME;
        private boolean logSql;

        private Builder(final @NotNull String jdbcUrl) {
            this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        }

        /**
         * @param username the database user
         * @return this builder
         */
        public @NotNull Builder username(final @Nullable String username) {
            this.username = username;
            return this;
        }

        /**
         * @param password the database password
         * @return this builder
         */
        public @NotNull Builder password(final @Nullable String password) {
            this.password = password;
            return this;
        }

        /**
         * @param poolName the pool name shown in logs and JMX
         * @return this builder
         */
        public @NotNull Builder poolName(final @NotNull String poolName) {
            this.poolName = Objects.requireNonNull(poolName, "poolName");
            return this;
        }

        /**
         * @param maximumPoolSize the maximum number of connections
         * @return this builder
         */
        public @NotNull Builder maximumPoolSize(final int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
            return this;
        }

        /**
         * @param minimumIdle the number of idle connections to keep, defaults to {@code maximumPoolSize}
         * @return this builder
         */
        public @NotNull Builder minimumIdle(final int minimumIdle) {
            this.minimumIdle = minimumIdle;
            return this;
        }

        /**
         * @param connectionTimeout how long to wait for a connection
         * @return this builder
         */
        public @NotNull Builder connectionTimeout(final @NotNull Duration connectionTimeout) {
            this.connectionTimeout = Objects.requireNonNull(connectionTimeout, "connectionTimeout");
            return this;
        }

        /**
         * @param idleTimeout how long a connection may sit idle, {@link Duration#ZERO} disables retirement
         * @return this builder
         */
        public @NotNull Builder idleTimeout(final @NotNull Duration idleTimeout) {
            this.idleTimeout = Objects.requireNonNull(idleTimeout, "idleTimeout");
            return this;
        }

        /**
         * @param maxLifetime the maximum age of a pooled connection
         * @return this builder
         */
        public @NotNull Builder maxLifetime(final @NotNull Duration maxLifetime) {
            this.maxLifetime = Objects.requireNonNull(maxLifetime, "maxLifetime");
            return this;
        }

        /**
         * @param logSql {@code true} to log every statement to SLF4J at {@code DEBUG}, defaults to {@code false}
         * @return this builder
         */
        public @NotNull Builder logSql(final boolean logSql) {
            this.logSql = logSql;
            return this;
        }

        /**
         * @return the immutable configuration
         * @throws IllegalArgumentException if the values do not form a valid configuration
         */
        public @NotNull DatabaseConfig build() {
            return new DatabaseConfig(
                    jdbcUrl,
                    username,
                    password,
                    poolName,
                    maximumPoolSize,
                    minimumIdle == null ? maximumPoolSize : minimumIdle,
                    connectionTimeout,
                    idleTimeout,
                    maxLifetime,
                    logSql);
        }
    }
}
