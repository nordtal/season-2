package eu.nordtal.s2.limbo.config;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.ConfigValidator;
import eu.nordtal.jcore.config.exception.ConfigException;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where {@code limbo}'s two config files live, and every rule about what a valid value is.
 * <p>
 * The same shape as {@code hunger-games}' and {@code network-control}'s own {@code Configs}: one
 * environment namespace per file, every check run once at startup rather than discovered mid-login.
 * A failure here disables the plugin and leaves the server running - which for this module means a
 * waiting room that accepts players and shows them nothing, so the log line is written to be found.
 * </p>
 */
public final class Configs {

    private Configs() {
    }

    public static @NotNull ConfigHandle<LimboSpec> load(final Path dataFolder, final Logger logger)
            throws ConfigException {
        return load(dataFolder, logger, "config", LimboSpec.class, "NORDTAL_LIMBO", config -> {
            requireText("world-name", config.worldName());
            requirePositive("title-refresh-seconds", config.titleRefreshSeconds());
            // Zero or negative here would not disable the watcher - AdminWatch floors the timer
            // at one second - so it would quietly become a query per second for the life of the
            // server. Refused by name instead.
            requirePositive("admin-poll-interval-seconds", config.adminPollIntervalSeconds());
            if (config.spawnY() < -60 || config.spawnY() > 300) {
                // Not a physics constraint - the world is empty - but a value outside the build
                // limits would put every player somewhere the server refuses to keep them.
                throw new IllegalArgumentException(
                        "spawn-y must be somewhere inside a world's build limits, was " + config.spawnY());
            }
        });
    }

    public static @NotNull ConfigHandle<DatabaseSpec> database(final Path dataFolder, final Logger logger)
            throws ConfigException {
        return load(dataFolder, logger, "database", DatabaseSpec.class, "NORDTAL_LIMBO_DATABASE", config -> {
            requireText("jdbc-url", config.jdbcUrl());
            requireText("username", config.username());
            if (!config.jdbcUrl().startsWith("jdbc:postgresql:")) {
                throw new IllegalArgumentException(
                        "jdbc-url must be a PostgreSQL URL (jdbc:postgresql://host:port/database)");
            }
            requirePositive("maximum-pool-size", config.maximumPoolSize());
            requirePositive("query-timeout-seconds", config.queryTimeoutSeconds());
        });
    }

    private static <T> ConfigHandle<T> load(final Path dataFolder, final Logger logger, final String name,
                                            final Class<T> specType, final String envPrefix,
                                            final ConfigValidator<T> validator) throws ConfigException {
        final Path file = dataFolder.resolve(name + ".yml");
        final boolean fresh = !Files.isRegularFile(file);

        final ConfigHandle<T> handle = ConfigLoader.builder(file, specType)
                .envPrefix(envPrefix)
                .validator(validator)
                .load();

        if (fresh) {
            logger.warn("No config existed at {} - defaults were written and are almost certainly "
                    + "not what you want", file.toAbsolutePath());
        }
        return handle;
    }

    private static void requireText(final String key, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " must not be empty");
        }
    }

    private static void requirePositive(final String key, final long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be greater than zero, was " + value);
        }
    }
}
