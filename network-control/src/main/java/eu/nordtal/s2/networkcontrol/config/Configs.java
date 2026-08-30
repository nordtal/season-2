package eu.nordtal.s2.networkcontrol.config;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.ConfigValidator;
import eu.nordtal.jcore.config.exception.ConfigException;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where {@code network-control}'s config files live, and every rule about what a valid value is.
 * <p>
 * Same shape as {@code access-bot}'s {@code Configs}: each file gets its own environment
 * namespace, and every check runs once at startup rather than being discovered mid-login. A
 * Velocity plugin has no {@code getDataFolder()} the way a Paper plugin does, so the directory is
 * handed in by the caller - Velocity injects it as {@code @DataDirectory Path}, which is
 * {@code plugins/network-control/} for a normal install.
 * </p>
 */
public final class Configs {

    private Configs() {
    }

    public static @NotNull ConfigHandle<DatabaseSpec> database(final Path directory, final Logger logger)
            throws ConfigException {
        return load(directory, logger, "database", DatabaseSpec.class, "NORDTAL_NETWORK_CONTROL_DATABASE", config -> {
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

    public static @NotNull ConfigHandle<GateSpec> gate(final Path directory, final Logger logger)
            throws ConfigException {
        return load(directory, logger, "gate", GateSpec.class, "NORDTAL_NETWORK_CONTROL_GATE", config -> {
            requirePositive("link-code-ttl-minutes", config.linkCodeTtlMinutes());
            requirePositive("fallback-cache-window-minutes", config.fallbackCacheWindowMinutes());
            requirePositive("expiry-check-interval-seconds", config.expiryCheckIntervalSeconds());
            requirePositive("expiry-warning-lead-minutes", config.expiryWarningLeadMinutes());
        });
    }

    // ------------------------------------------------------------------ loading

    private static <T> ConfigHandle<T> load(final Path directory, final Logger logger, final String name,
                                             final Class<T> specType, final String envPrefix,
                                             final ConfigValidator<T> validator) throws ConfigException {
        final Path file = directory.resolve(name + ".yml");
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

    // ------------------------------------------------------------------ validation helpers

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
