package eu.nordtal.s2.paymentsbot.config;

import eu.nordtal.jcore.config.JsonConfig;
import eu.nordtal.jcore.config.JsonConfigLoader;
import eu.nordtal.jcore.config.exception.ConfigException;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.File;

/**
 * Where the bot's JSON config files live. Thin wrapper over jcore's {@link JsonConfigLoader} so
 * the {@code config/} directory is named once rather than in every service that reads a file.
 * <p>
 * jcore's loader writes a defaults file when none exists, and reconciles an existing file against
 * the class (adding new fields, dropping removed ones) on every load. Its {@code ObjectMapper} is
 * set to {@code SNAKE_CASE}, so a {@code jdbcUrl} property is {@code "jdbc_url"} in the JSON.
 * </p>
 */
@Slf4j
public final class Configs {

    /** Mounted as a Docker volume; see the module Dockerfile. */
    private static final File DIRECTORY = new File("config");

    private Configs() {
    }

    /**
     * Reads {@code config/<name>.json} into {@code type}, writing a defaults file first if none
     * exists yet.
     *
     * @throws ConfigException if the file cannot be created, read or parsed. Callers decide
     *                         whether that is fatal - for the database settings it is.
     */
    public static @NotNull <T extends JsonConfig> T load(@NotNull final String name,
                                                         @NotNull final Class<T> type) throws ConfigException {
        final File file = new File(DIRECTORY, name + ".json");
        final boolean fresh = !file.exists();

        log.info("Loading config from {}", file.getAbsolutePath());
        final T config = JsonConfigLoader.load(file, type);

        if (fresh) {
            log.warn("No config existed at {} - defaults were written and are almost certainly not "
                    + "what you want", file.getAbsolutePath());
        }
        return config;
    }
}
