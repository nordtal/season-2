package eu.nordtal.s2.steward.worker.config;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.s2.common.config.EnvOverrideFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;

/** Loads {@code steward-worker}'s config files and checks every value once, at startup. */
public final class Configs {

    /** {@code owner/name}, the only form the GitHub API takes. */
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+");

    /** A Modrinth id is eight characters of its own base62 alphabet. */
    private static final Pattern MODRINTH_ID = Pattern.compile("[A-Za-z0-9]{8}");

    private Configs() {}

    public static ConfigHandle<DatabaseSpec> database(final Path directory, final Logger logger)
            throws ConfigException {
        final Path file = directory.resolve("database.yml");
        final boolean fresh = !Files.isRegularFile(file);

        final ConfigHandle<DatabaseSpec> handle = ConfigLoader.builder(file, DatabaseSpec.class)
                .envPrefix("NORDTAL_STEWARD_DATABASE")
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

        // localhost:5432 is not the database from inside a container, so there is no usable default.
        if (fresh) {
            logger.warn(
                    "No config existed at {} - defaults were written and are almost certainly" + " not what you want",
                    file.toAbsolutePath());
        }
        recordEnvironmentOverrides(handle, logger);
        return handle;
    }

    public static ConfigHandle<StewardSpec> steward(final Path directory, final Logger logger) throws ConfigException {
        final Path file = directory.resolve("steward.yml");
        final boolean fresh = !Files.isRegularFile(file);

        final ConfigHandle<StewardSpec> handle = ConfigLoader.builder(file, StewardSpec.class)
                .envPrefix("NORDTAL_STEWARD")
                .validator(config -> {
                    requireRepo("season-repo", config.seasonRepo());
                    requireRepo("display-tags-repo", config.displayTagsRepo());
                    requireModrinthId("packetevents-project", config.packetEventsProject());
                    requireText("volumes-root", config.volumesRoot());
                    requirePositive("http-timeout-seconds", config.httpTimeoutSeconds());
                    requirePositive("download-timeout-seconds", config.downloadTimeoutSeconds());
                    requireBackup(config.backup());
                    requireBunq(config.bunq());
                })
                .load();

        // A fresh steward.yml is usable as written, since the defaults are the real values.
        if (fresh) {
            logger.info(
                    "No config existed at {} - it was written with this project's own defaults", file.toAbsolutePath());
        }
        recordEnvironmentOverrides(handle, logger);
        return handle;
    }

    /**
     * Writes {@code handle}'s environment overrides next to its file, best-effort.
     *
     * {@code configfile.EnvOverrides} reads it to warn that an overridden setting cannot be edited.
     */
    private static void recordEnvironmentOverrides(final ConfigHandle<?> handle, final Logger logger) {
        try {
            EnvOverrideFile.write(handle.file(), handle.environmentOverrides());
        } catch (final IOException e) {
            logger.warn("Could not write the environment-override marker beside {}: {}", handle.file(), e.getMessage());
        }
    }

    /**
     * Refuses a half-filled bunq block by name; empty is allowed and means no bank account.
     *
     * A non-numeric account id is refused here too, at the start rather than in the poll loop.
     */
    private static void requireBunq(final StewardSpec.BunqSpec bunq) {
        final boolean key = isSet(bunq.apiKey());
        final boolean account = isSet(bunq.accountId());
        if (key != account) {
            throw new IllegalArgumentException("bunq needs both api-key and account-id or neither,"
                    + " and only " + (key ? "api-key" : "account-id") + " is set. Leave both empty"
                    + " to run without payments. If this deployment used to work, check whether its"
                    + " environment file still says NORDTAL_BOT_BUNQ_* - those two variables became"
                    + " NORDTAL_STEWARD_BUNQ_* when bunq moved into this container.");
        }
        if (account) {
            try {
                Long.parseLong(bunq.accountId().trim());
            } catch (final NumberFormatException e) {
                throw new IllegalArgumentException("bunq.account-id must be a number, was '" + bunq.accountId() + "'");
            }
        }

        requirePositive("bunq.poll-interval-seconds", bunq.pollIntervalSeconds());
        requirePositive("bunq.recent-payment-count", bunq.recentPaymentCount());

        // Blank is normal: the first start stamps its own instant.
        final String watermark = bunq.watermark();
        if (watermark != null && !watermark.isBlank()) {
            try {
                java.time.Instant.parse(watermark.trim());
            } catch (final java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException("bunq.watermark must be empty or an ISO-8601"
                        + " instant such as 2026-09-01T00:00:00Z, was: " + watermark);
            }
        }
    }

    private static boolean isSet(final String value) {
        return value != null && !value.isBlank();
    }

    /** Refuses {@code postgres-data} as a backup volume, since a snapshot of a live PGDATA is torn. */
    private static void requireBackup(final BackupSpec backup) {
        requirePositive("backup.patience-minutes", backup.patienceMinutes());
        // The forbidden entry is reported before the missing one.
        for (final String volume : backup.volumes()) {
            if (volume != null && volume.endsWith("postgres-data")) {
                throw new IllegalArgumentException("backup.volumes lists '" + volume + "'. A"
                        + " snapshot of a running PostgreSQL data directory is torn, and it fails"
                        + " when somebody tries to RESTORE it rather than now - which is the worst"
                        + " place for it to fail. The pg_dump sidecar writes postgres-dumps; list"
                        + " that instead. See deploy/README.md#backups.");
            }
        }
        requireTheWorld(backup.volumes());
    }

    /** Requires the world volume in the list, since it is the one volume that cannot be rebuilt. */
    private static void requireTheWorld(final List<String> volumes) {
        if (volumes.stream().noneMatch(volume -> volume != null && volume.endsWith("mc-smp"))) {
            throw new IllegalArgumentException("backup.volumes does not list the smp world volume"
                    + " (a name ending in mc-smp), and it is not optional: a backup that saved"
                    + " everything except the world would still report DONE every night. Nordtal is"
                    + " the one thing in this deployment that is in no repository and in no"
                    + " release. See deploy/README.md#backups.");
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
            // A slug only fails as an id when the author renames it, so it is caught here.
            throw new IllegalArgumentException(key + " must be a Modrinth project id: eight"
                    + " alphanumeric characters, not the slug. Read it from the 'project_id' field"
                    + " of any version, or from a cdn.modrinth.com/data/<id>/ URL. Was '"
                    + value + "'");
        }
    }
}
