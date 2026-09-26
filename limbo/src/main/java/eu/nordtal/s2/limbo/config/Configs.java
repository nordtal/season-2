package eu.nordtal.s2.limbo.config;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.ConfigValidator;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.s2.common.config.EnvOverrideFile;
import eu.nordtal.s2.common.message.Tone;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;

/**
 * Where {@code limbo}'s two config files live, and every rule about what a valid value is.
 *
 * The same shape as {@code hunger-games}' and {@code proxy}'s own {@code Configs}: one environment namespace per
 * file, every check run once at startup rather than discovered mid-login. A failure here disables the plugin and
 * leaves the server running - which for this module means a waiting room that accepts players and shows them
 * nothing, so the log line is written to be found.
 */
public final class Configs {

    private Configs() {}

    public static ConfigHandle<LimboSpec> load(final Path dataFolder, final Logger logger) throws ConfigException {
        return load(dataFolder, logger, "config", LimboSpec.class, "NORDTAL_LIMBO", config -> {
            requireText("world-name", config.worldName());
            requirePositive("title-refresh-seconds", config.titleRefreshSeconds());
            // AdminWatch floors the timer at one second, so zero or negative would become a query per second.
            requirePositive("admin-poll-interval-seconds", config.adminPollIntervalSeconds());
            if (config.spawnY() < -60 || config.spawnY() > 300) {
                // The world is empty, but a value outside the build limits still refuses to keep a player there.
                throw new IllegalArgumentException(
                        "spawn-y must be somewhere inside a world's build limits, was " + config.spawnY());
            }
        });
    }

    public static ConfigHandle<DatabaseSpec> database(final Path dataFolder, final Logger logger)
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

    /**
     * Loads the tone colours.
     *
     * No validator: {@code ToneColours#parse} corrects a bad hex value where it parses it, so a
     * typo here is never a reason to refuse a login. Read once at enable - see {@code ColoursSpec}'s
     * own javadoc for why this file has no {@code /limbo reload} path yet.
     */
    public static ConfigHandle<ColoursSpec> colours(final Path dataFolder, final Logger logger) throws ConfigException {
        return load(dataFolder, logger, "colours", ColoursSpec.class, "NORDTAL_LIMBO_COLOURS", config -> {});
    }

    /**
     * {@code ColoursSpec}'s five accessors, as the map {@code ToneColours} parses.
     *
     * An exhaustive {@code switch} with no {@code default}, so a sixth {@link Tone} stops this
     * compiling rather than silently leaving it unpainted.
     */
    public static Map<Tone, String> declared(final ColoursSpec spec) {
        final Map<Tone, String> declared = new EnumMap<>(Tone.class);
        for (final Tone tone : Tone.values()) {
            declared.put(
                    tone,
                    switch (tone) {
                        case GOOD -> spec.good();
                        case BAD -> spec.bad();
                        case WARN -> spec.warn();
                        case NEUTRAL -> spec.neutral();
                        case MUTED -> spec.muted();
                    });
        }
        return declared;
    }

    private static <T> ConfigHandle<T> load(
            final Path dataFolder,
            final Logger logger,
            final String name,
            final Class<T> specType,
            final String envPrefix,
            final ConfigValidator<T> validator)
            throws ConfigException {
        final Path file = dataFolder.resolve(name + ".yml");
        final boolean fresh = !Files.isRegularFile(file);

        final ConfigHandle<T> handle = ConfigLoader.builder(file, specType)
                .envPrefix(envPrefix)
                .validator(validator)
                .load();

        if (fresh) {
            logger.warn(
                    "No config existed at {} - defaults were written and are almost certainly " + "not what you want",
                    file.toAbsolutePath());
        }
        recordEnvironmentOverrides(handle, logger);
        return handle;
    }

    /**
     * Writes {@code handle}'s {@link ConfigHandle#environmentOverrides()} marker next to its file.
     *
     * That lets steward-worker warn that editing an overridden setting there has no effect until
     * the variable is removed. Best-effort: this is a UI nicety, not a reason for a correctly
     * loaded config to refuse to enable the plugin.
     */
    private static void recordEnvironmentOverrides(final ConfigHandle<?> handle, final Logger logger) {
        try {
            EnvOverrideFile.write(handle.file(), handle.environmentOverrides());
        } catch (final IOException e) {
            logger.warn("Could not write the environment-override marker beside {}: {}", handle.file(), e.getMessage());
        }
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
