package eu.nordtal.s2.updater.config;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.exception.ConfigException;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Where {@code updater}'s config files live, and every rule about what a valid value is. Every check
 * runs once at startup rather than being discovered half way through a resolve.
 */
public final class Configs {

    /** {@code owner/name}, the only form the GitHub API takes. */
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+");

    /** A Modrinth id is eight characters of its own base62 alphabet. */
    private static final Pattern MODRINTH_ID = Pattern.compile("[A-Za-z0-9]{8}");

    private Configs() {
    }

    public static @NotNull ConfigHandle<DatabaseSpec> database(final Path directory, final Logger logger)
            throws ConfigException {
        final Path file = directory.resolve("database.yml");
        final boolean fresh = !Files.isRegularFile(file);

        final ConfigHandle<DatabaseSpec> handle = ConfigLoader.builder(file, DatabaseSpec.class)
                .envPrefix("NORDTAL_UPDATER_DATABASE")
                .validator(config -> {
                    requireText("jdbc-url", config.jdbcUrl());
                    requireText("username", config.username());
                    if (!config.jdbcUrl().startsWith("jdbc:postgresql:")) {
                        throw new IllegalArgumentException(
                                "jdbc-url must be a PostgreSQL URL (jdbc:postgresql://host:port/database)");
                    }
                    requirePositive("maximum-pool-size", config.maximumPoolSize());
                    requirePositive("query-timeout-seconds", config.queryTimeoutSeconds());
                })
                .load();

        // This config has no usable default: localhost:5432 is not where the database is from
        // inside a container, and the alternative to saying so is a connection refused.
        if (fresh) {
            logger.warn("No config existed at {} - defaults were written and are almost certainly"
                    + " not what you want", file.toAbsolutePath());
        }
        return handle;
    }

    public static @NotNull ConfigHandle<UpdaterSpec> updater(final Path directory, final Logger logger)
            throws ConfigException {
        final Path file = directory.resolve("updater.yml");
        final boolean fresh = !Files.isRegularFile(file);

        final ConfigHandle<UpdaterSpec> handle = ConfigLoader.builder(file, UpdaterSpec.class)
                .envPrefix("NORDTAL_UPDATER")
                .validator(config -> {
                    requireRepo("season-repo", config.seasonRepo());
                    requireRepo("display-tags-repo", config.displayTagsRepo());
                    requireModrinthId("packetevents-project", config.packetEventsProject());
                    requireModrinthId("chunky-project", config.chunkyProject());
                    requireText("volumes-root", config.volumesRoot());
                    requirePositive("http-timeout-seconds", config.httpTimeoutSeconds());
                    requirePositive("download-timeout-seconds", config.downloadTimeoutSeconds());
                    requirePositive("poll-interval-seconds", config.pollIntervalSeconds());
                    requireArcane(config.arcane());
                    requireBackup(config.backup());
                })
                .load();

        // A fresh updater.yml is usable as written: the defaults are the real nordtal.eu values.
        if (fresh) {
            logger.info("No config existed at {} - it was written with this project's own defaults",
                    file.toAbsolutePath());
        }
        return handle;
    }

    // ------------------------------------------------------------------ validation helpers

    /**
     * What a backup may be pointed at. {@code postgres-data} is refused by name: a snapshot of a
     * live PGDATA is torn, and that surfaces as a {@code pg_restore} failing months later rather
     * than as an error here. The pg_dump sidecar writes {@code postgres-dumps} instead.
     */
    private static void requireBackup(final UpdaterSpec.BackupSpec backup) {
        requirePositive("backup.patience-minutes", backup.patienceMinutes());
        for (final String volume : backup.volumes()) {
            if (volume != null && volume.endsWith("postgres-data")) {
                throw new IllegalArgumentException("backup.volumes lists '" + volume + "'. A"
                        + " snapshot of a running PostgreSQL data directory is torn, and it fails"
                        + " when somebody tries to RESTORE it rather than now - which is the worst"
                        + " place for it to fail. The pg_dump sidecar writes postgres-dumps; list"
                        + " that instead. See deploy/README.md#backups.");
            }
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

    /**
     * The restart settings, which are all optional together. An empty {@code base-url} is supported:
     * the updater then does everything except the restart and says so. Half-configured is not - a
     * base URL with no token would fail with a 401 at the one moment somebody is waiting on it.
     */
    private static void requireArcane(final UpdaterSpec.ArcaneSpec arcane) {
        if (arcane.baseUrl().isBlank()) {
            return;
        }
        if (!arcane.baseUrl().startsWith("http://") && !arcane.baseUrl().startsWith("https://")) {
            throw new IllegalArgumentException(
                    "arcane.base-url must be an http(s) origin, was '" + arcane.baseUrl() + "'");
        }
        if (arcane.baseUrl().endsWith("/")) {
            // Refused rather than trimmed, so the file and the request agree.
            throw new IllegalArgumentException(
                    "arcane.base-url must not end in a slash - redeploy-path already starts with one");
        }
        if (arcane.apiKey().isBlank()) {
            throw new IllegalArgumentException("arcane.api-key must be set when arcane.base-url is."
                    + " Generate one in Arcane under Settings -> API Keys, and prefer"
                    + " NORDTAL_UPDATER_ARCANE_API_KEY over writing it into this file");
        }
        requireText("arcane.environment", arcane.environment());
        if (arcane.project().isBlank()) {
            throw new IllegalArgumentException("arcane.project must be set when arcane.base-url is."
                    + " It is the project's ID - a UUID Arcane generated - and NOT the compose"
                    + " project name 'nordtal-s2'. Read it from the browser URL with the project"
                    + " open, or from GET " + arcane.baseUrl() + "/api/environments/"
                    + arcane.environment() + "/projects");
        }
        if (!arcane.redeployPath().startsWith("/")) {
            throw new IllegalArgumentException(
                    "arcane.redeploy-path must start with a slash, was '" + arcane.redeployPath() + "'");
        }
        requirePositive("arcane.timeout-seconds", arcane.timeoutSeconds());
    }

    private static void requireRepo(final String key, final String value) {
        requireText(key, value);
        if (!REPO.matcher(value).matches()) {
            throw new IllegalArgumentException(key + " must be a GitHub repository as owner/name"
                    + " - not a URL and not just the name - was '" + value + "'");
        }
    }

    private static void requireModrinthId(final String key, final String value) {
        requireText(key, value);
        if (!MODRINTH_ID.matcher(value).matches()) {
            // A slug passes as text and only fails as an id when the author renames it - months
            // later, looking like an outage. Caught here instead.
            throw new IllegalArgumentException(key + " must be a Modrinth project id: eight"
                    + " alphanumeric characters, not the slug. Read it from the 'project_id' field"
                    + " of any version, or from a cdn.modrinth.com/data/<id>/ URL. Was '"
                    + value + "'");
        }
    }
}
