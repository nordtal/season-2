package eu.nordtal.s2.steward.worker.config;

import eu.nordtal.s2.settings.Checks;
import eu.nordtal.s2.settings.DatabasePool;
import eu.nordtal.s2.settings.DatabaseSpec;
import eu.nordtal.s2.settings.FileSettings;
import eu.nordtal.s2.settings.Setting;
import eu.nordtal.s2.settings.Settings;
import eu.nordtal.s2.settings.SettingsException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;

/** Loads {@code steward-worker}'s config files and checks every value once, at startup. */
public final class WorkerSettings {

    /** {@code owner/name}, the only form the GitHub API takes. */
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+");

    /** A Modrinth id is eight characters of its own base62 alphabet. */
    private static final Pattern MODRINTH_ID = Pattern.compile("[A-Za-z0-9]{8}");

    private WorkerSettings() {}

    public static Setting<DatabaseSpec> database(final Path directory, final Logger logger) throws SettingsException {
        return settings(directory, logger).load("database", DatabaseSpec.class, DatabasePool::check);
    }

    /** Loads {@code steward.yml}, whose defaults are the real values. */
    public static Setting<StewardSpec> steward(final Path directory, final Logger logger) throws SettingsException {
        return settings(directory, logger).load("steward", StewardSpec.class, WorkerSettings::check);
    }

    private static void check(final StewardSpec config) {
        requireRepo("season-repo", config.seasonRepo());
        requireRepo("display-tags-repo", config.displayTagsRepo());
        requireModrinthId("packetevents-project", config.packetEventsProject());
        Checks.requireText("volumes-root", config.volumesRoot());
        Checks.requirePositive("http-timeout-seconds", config.httpTimeoutSeconds());
        Checks.requirePositive("download-timeout-seconds", config.downloadTimeoutSeconds());
        requireBackup(config.backup());
        requireBunq(config.bunq());
    }

    private static Settings settings(final Path directory, final Logger logger) {
        return FileSettings.in(directory, "NORDTAL_STEWARD", "steward", logger);
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

        Checks.requirePositive("bunq.poll-interval-seconds", bunq.pollIntervalSeconds());
        Checks.requirePositive("bunq.recent-payment-count", bunq.recentPaymentCount());

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
        Checks.requirePositive("backup.patience-minutes", backup.patienceMinutes());
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

    private static void requireRepo(final String key, final String value) {
        Checks.requireText(key, value);
        if (!REPO.matcher(value).matches()) {
            throw new IllegalArgumentException(key + " must be a GitHub repository as owner/name"
                    + " - not a URL and not just the name - was '" + value + "'");
        }
    }

    private static void requireModrinthId(final String key, final String value) {
        Checks.requireText(key, value);
        if (!MODRINTH_ID.matcher(value).matches()) {
            // A slug only fails as an id when the author renames it, so it is caught here.
            throw new IllegalArgumentException(key + " must be a Modrinth project id: eight"
                    + " alphanumeric characters, not the slug. Read it from the 'project_id' field"
                    + " of any version, or from a cdn.modrinth.com/data/<id>/ URL. Was '"
                    + value + "'");
        }
    }
}
