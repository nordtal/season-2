package eu.nordtal.s2.steward.ui.config;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.exception.ConfigException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The two files this service reads, and what has to be true of them before it starts.
 *
 * <p>Validation happens at load, not at first use. An interface that starts happily and fails on
 * the first click is one whose failure lands on whoever clicked; one that refuses to start names
 * the key and the file while somebody is still looking at a deploy.</p>
 */
public final class Configs {

    private Configs() {
    }

    public static @NotNull ConfigHandle<UiSpec> ui(final @NotNull Path directory,
                                                   final @NotNull Logger logger)
            throws ConfigException {
        final Path file = directory.resolve("steward-ui.yml");
        final boolean fresh = !Files.isRegularFile(file);

        final ConfigHandle<UiSpec> handle = ConfigLoader.builder(file, UiSpec.class)
                .envPrefix("NORDTAL_STEWARD_UI")
                .validator(config -> {
                    requirePositive("port", config.port());
                    requirePositive("session-hours", config.sessionHours());
                    final String url = config.publicUrl();
                    if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
                        throw new IllegalArgumentException(
                                "public-url must start with http:// or https:// - Discord is given "
                                + "it verbatim as the redirect URI and refuses anything else");
                    }
                    if (url.endsWith("/")) {
                        throw new IllegalArgumentException(
                                "public-url must not end with a slash, or the redirect URI would "
                                + "have two and Discord would not match it");
                    }
                    if (config.worker() == null || config.worker().baseUrl().isBlank()) {
                        throw new IllegalArgumentException("worker.base-url is empty");
                    }
                })
                .load();

        if (fresh) {
            logger.info("No config existed at {} - it was written with this project's defaults."
                    + " The two secrets are environment variables and are not in it.",
                    file.toAbsolutePath());
        }
        return handle;
    }

    public static @NotNull ConfigHandle<DatabaseSpec> database(final @NotNull Path directory,
                                                               final @NotNull Logger logger)
            throws ConfigException {
        final Path file = directory.resolve("database.yml");
        final boolean fresh = !Files.isRegularFile(file);

        final ConfigHandle<DatabaseSpec> handle = ConfigLoader.builder(file, DatabaseSpec.class)
                .envPrefix("NORDTAL_STEWARD_UI_DATABASE")
                .validator(config -> {
                    if (config.jdbcUrl() == null || !config.jdbcUrl().startsWith("jdbc:postgresql://")) {
                        throw new IllegalArgumentException(
                                "jdbc-url must be a PostgreSQL URL (jdbc:postgresql://host:port/db)");
                    }
                    requirePositive("maximum-pool-size", config.maximumPoolSize());
                })
                .load();

        if (fresh) {
            logger.warn("No database config existed at {} - defaults were written, and localhost"
                    + " is not where the database is from inside a container",
                    file.toAbsolutePath());
        }
        return handle;
    }

    private static void requirePositive(final String key, final int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be greater than zero, not " + value);
        }
    }
}
