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
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

/**
 * Where {@code steward-worker}'s config files live, and every rule about what a valid value is.
 * Every check runs once at startup rather than being discovered half way through a resolve.
 */
public final class Configs {

    /** {@code owner/name}, the only form the GitHub API takes. */
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+");

    /** A Modrinth id is eight characters of its own base62 alphabet. */
    private static final Pattern MODRINTH_ID = Pattern.compile("[A-Za-z0-9]{8}");

    private Configs() {}

    public static @NotNull ConfigHandle<DatabaseSpec> database(final Path directory, final Logger logger)
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

        // This config has no usable default: localhost:5432 is not where the database is from
        // inside a container, and the alternative to saying so is a connection refused.
        if (fresh) {
            logger.warn(
                    "No config existed at {} - defaults were written and are almost certainly" + " not what you want",
                    file.toAbsolutePath());
        }
        recordEnvironmentOverrides(handle, logger);
        return handle;
    }

    public static @NotNull ConfigHandle<StewardSpec> steward(final Path directory, final Logger logger)
            throws ConfigException {
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
                    requirePositive("poll-interval-seconds", config.pollIntervalSeconds());
                    requireBackup(config.backup());
                    requireBunq(config.bunq());
                })
                .load();

        // A fresh steward.yml is usable as written: the defaults are the real nordtal.eu values.
        if (fresh) {
            logger.info(
                    "No config existed at {} - it was written with this project's own defaults", file.toAbsolutePath());
        }
        recordEnvironmentOverrides(handle, logger);
        return handle;
    }

    /**
     * Writes {@code handle}'s {@link ConfigHandle#environmentOverrides()} next to its file, so this
     * same process's {@code configfile.EnvOverrides} can warn that editing an overridden setting
     * through Steward has no effect until the variable is removed (steward/76). Best-effort: this
     * is a UI nicety, not a reason for a correctly loaded config to refuse to start the worker.
     */
    private static void recordEnvironmentOverrides(final ConfigHandle<?> handle, final Logger logger) {
        try {
            EnvOverrideFile.write(handle.file(), handle.environmentOverrides());
        } catch (final IOException e) {
            logger.warn("Could not write the environment-override marker beside {}: {}", handle.file(), e.getMessage());
        }
    }

    /**
     * What bunq has to get right before this container is allowed to touch a bank account
     * (steward/109; the same three rules {@code discord-bot}'s {@code Configs.bot()} used to hold).
     *
     * <h2>Empty is allowed, half is not</h2>
     * A season with no bank account is a season whose network does everything except take money, and
     * it has to be able to start - the account is the one thing here that cannot be created from a
     * terminal. Half of it is always a setup that stopped in the middle, or an environment file that
     * was renamed in one place and not the other, so it is refused by name rather than run.
     *
     * <h2>The account id is parsed here</h2>
     * Not in the poll loop minutes later, and not inside a Discord interaction: a non-numeric id is
     * a value somebody typed, and the place to say so is the start.
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

        // Blank is the normal case: the first start stamps its own instant into bot_setting and
        // every later start reads it back. A value here is an explicit override and has to be
        // readable, because an unreadable one would surface as a poll that books nothing.
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

    // ------------------------------------------------------------------ validation helpers

    private static boolean isSet(final String value) {
        return value != null && !value.isBlank();
    }

    /**
     * What a backup may be pointed at. {@code postgres-data} is refused by name: a snapshot of a
     * live PGDATA is torn, and that surfaces as a {@code pg_restore} failing months later rather
     * than as an error here. The pg_dump sidecar writes {@code postgres-dumps} instead.
     */
    private static void requireBackup(final StewardSpec.BackupSpec backup) {
        requirePositive("backup.patience-minutes", backup.patienceMinutes());
        // The forbidden entry is reported before the missing one: a list naming postgres-data is
        // an operator who wrote something wrong, and telling them about a different volume first
        // would send them off fixing the wrong line.
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

    /**
     * The world is the one volume that cannot be rebuilt, so it has to be in this list.
     *
     * <h2>What a successful backup is taken to prove</h2>
     * Until 2026-09-20 this rule had a second, sharper reason: {@code smp} refused to reset the
     * farm world unless a successful backup sat behind it, and "successful" meant this service
     * reported {@code DONE} - which said nothing about <em>what</em> was saved. A list that kept
     * {@code bot-config} and dropped {@code mc-smp} produced a perfectly successful backup every
     * night while the one volume the guarantee was about was in no archive anywhere. The farm
     * world and its reset went with season-2-ingame/30, and the hole they exposed did not.
     *
     * <p>Refused at load, therefore, and not warned about: the cost of being wrong here is Nordtal,
     * which is in no repository and in no release, and the operator who edits this list is not the
     * one who finds out.</p>
     */
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
            // A slug passes as text and only fails as an id when the author renames it - months
            // later, looking like an outage. Caught here instead.
            throw new IllegalArgumentException(key + " must be a Modrinth project id: eight"
                    + " alphanumeric characters, not the slug. Read it from the 'project_id' field"
                    + " of any version, or from a cdn.modrinth.com/data/<id>/ URL. Was '"
                    + value + "'");
        }
    }
}
