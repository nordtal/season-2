package eu.nordtal.s2.steward.config;

import eu.nordtal.s2.database.setting.SettingStore;
import eu.nordtal.s2.settings.Checks;
import eu.nordtal.s2.settings.DatabasePool;
import eu.nordtal.s2.settings.DatabaseSettings;
import eu.nordtal.s2.settings.DatabaseSpec;
import eu.nordtal.s2.settings.Environment;
import eu.nordtal.s2.settings.EnvironmentSettings;
import eu.nordtal.s2.settings.Group;
import eu.nordtal.s2.settings.Setting;
import eu.nordtal.s2.settings.Settings;
import eu.nordtal.s2.settings.SettingsException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Steward's settings, each value checked once at load, so a bad one names its key and group at startup.
 *
 * The connection comes from the environment; {@code steward} and {@code web} live in the database.
 */
public final class StewardSettings {

    /** The service whose settings these are. */
    public static final String SERVICE = "steward";

    /** Steward's environment: {@code NORDTAL_STEWARD} for the steward group, {@code NORDTAL_STEWARD_<GROUP>} else. */
    public static final Environment ENVIRONMENT =
            Environment.of("NORDTAL_STEWARD").withMain("steward");

    /** {@code owner/name}, the only form the GitHub API takes. */
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+");

    /** A Modrinth id is eight characters of its own base62 alphabet. */
    private static final Pattern MODRINTH_ID = Pattern.compile("[A-Za-z0-9]{8}");

    private static final String LOCALHOST = "localhost";

    private StewardSettings() {}

    /** Returns the steward group, whose defaults are the real values; a change re-arms the two clocks. */
    public static Group<StewardSpec> stewardGroup() {
        return Group.of("steward", StewardSpec.class)
                .checkedBy(StewardSettings::checkSteward)
                .whileRunning();
    }

    /** Loads the steward group. */
    public static Setting<StewardSpec> steward(final Settings settings) throws SettingsException {
        return settings.load(stewardGroup());
    }

    /** Returns the alerts group, which applies while steward runs, at the monitor's next reading. */
    public static Group<AlertsSpec> alertsGroup() {
        return Group.of("alerts", AlertsSpec.class)
                .checkedBy(StewardSettings::checkAlerts)
                .whileRunning();
    }

    /** Loads the alerts group. */
    public static Setting<AlertsSpec> alerts(final Settings settings) throws SettingsException {
        return settings.load(alertsGroup());
    }

    /** Loads the web group; its secrets are environment variables and never stored. */
    public static Setting<WebSpec> web(final Settings settings) throws SettingsException {
        return settings.load(Group.of("web", WebSpec.class).checkedBy(StewardSettings::checkWeb));
    }

    /** Loads the connection to the database from the environment, refusing an empty password. */
    public static Setting<DatabaseSpec> database() throws SettingsException {
        return EnvironmentSettings.of(ENVIRONMENT)
                .load(Group.of("database", DatabaseSpec.class).checkedBy(StewardSettings::checkDatabase));
    }

    /** Returns steward's settings in the database behind {@code dataSource}. */
    public static DatabaseSettings stored(final DataSource dataSource, final Logger logger) {
        return DatabaseSettings.over(SettingStore.using(dataSource), SERVICE, ENVIRONMENT, logger);
    }

    /** Returns steward's settings, importing the files the last installation left in {@code directory} once. */
    public static DatabaseSettings importing(final DataSource dataSource, final Path directory, final Logger logger) {
        return stored(dataSource, logger).importingFrom(directory, Set.of());
    }

    private static void checkSteward(final StewardSpec config) {
        requireRepo("season-repo", config.seasonRepo());
        requireRepo("display-tags-repo", config.displayTagsRepo());
        requireModrinthId("packetevents-project", config.packetEventsProject());
        Checks.requireText("volumes-root", config.volumesRoot());
        Checks.requirePositive("http-timeout-seconds", config.httpTimeoutSeconds());
        Checks.requirePositive("download-timeout-seconds", config.downloadTimeoutSeconds());
        requireBackup(config.backup());
        requireBunq(config.bunq());
    }

    private static void checkWeb(final WebSpec config) {
        Checks.requirePositive("port", config.port());
        Checks.requirePositive("session-days", config.sessionDays());
        // A ceiling too: it is multiplied into a cookie's Max-Age, which is an int.
        if (config.sessionDays() > 365) {
            throw new IllegalArgumentException("session-days is " + config.sessionDays()
                    + " - a session lasting longer than a season is not a session");
        }
        requirePublicUrl(config.publicUrl());
        requireRelyingParty(config.webauthn() == null ? null : config.webauthn().relyingPartyId(), config.publicUrl());
    }

    private static void checkAlerts(final AlertsSpec config) {
        Checks.requirePositive("disk-percent", config.diskPercent());
        Checks.requirePositive("memory-percent", config.memoryPercent());
        Checks.requirePositive("backup-age-hours", config.backupAgeHours());
    }

    private static void checkDatabase(final DatabaseSpec config) {
        DatabasePool.check(config);
        if (config.jdbcUrl() == null || !config.jdbcUrl().startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("jdbc-url must be a PostgreSQL URL (jdbc:postgresql://host:port/db)");
        }
        if (config.password() == null || config.password().isBlank()) {
            // Refused here rather than three seconds later as a pool that cannot connect.
            throw new IllegalArgumentException("password is empty."
                    + " NORDTAL_STEWARD_DATABASE_PASSWORD is what compose.yml sets"
                    + " from POSTGRES_PASSWORD; an empty one means the variable did not"
                    + " reach this container");
        }
        Checks.requirePositive("maximum-pool-size", config.maximumPoolSize());
    }

    /** Refuses a poll or a watermark that cannot work; the credentials are steward-bunq's and checked there. */
    private static void requireBunq(final StewardSpec.BunqSpec bunq) {
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

    private static void requireBackup(final BackupSpec backup) {
        Checks.requirePositive("backup.patience-minutes", backup.patienceMinutes());
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

    /**
     * The address this interface answers on, parsed since Discord compares the redirect URI built from it as a string.
     */
    static void requirePublicUrl(final @Nullable String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("public-url is empty - it is the address this"
                    + " interface answers on, e.g. https://steward.dev.nordtal.eu");
        }
        if (url.endsWith("/")) {
            throw new IllegalArgumentException("public-url must not end with a slash, or the redirect URI would "
                    + "have two and Discord would not match it");
        }
        final URI parsed;
        try {
            parsed = new URI(url);
        } catch (URISyntaxException notAUrl) {
            throw new IllegalArgumentException("public-url is not a URL: " + notAUrl.getMessage());
        }
        final String scheme = parsed.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new IllegalArgumentException("public-url must start with http:// or https:// - Discord is given "
                    + "it verbatim as the redirect URI and refuses anything else, was " + url);
        }
        if (parsed.getHost() == null || parsed.getHost().isBlank()) {
            throw new IllegalArgumentException("public-url names no host: " + url);
        }
        if (parsed.getQuery() != null || parsed.getFragment() != null) {
            throw new IllegalArgumentException("public-url carries a query or a fragment: " + url
                    + ". The redirect URI is this plus /auth/callback, and Discord compares it as"
                    + " a string");
        }
    }

    /** Holds the security keys' domain against public-url, since a browser refuses a mismatch in silence. */
    static void requireRelyingParty(final @Nullable String relyingPartyId, final String publicUrl) {
        if (relyingPartyId == null || relyingPartyId.isBlank()) {
            throw new IllegalArgumentException("webauthn.relying-party-id is empty - it is the"
                    + " domain every security key is registered against, e.g. nordtal.eu");
        }
        final String id = relyingPartyId.trim().toLowerCase(java.util.Locale.ROOT);
        if (id.contains("/") || id.contains(":")) {
            throw new IllegalArgumentException("webauthn.relying-party-id is a DOMAIN, not a URL"
                    + " - no scheme, no port, no path. Was: " + relyingPartyId);
        }
        // At least two labels catches a bare TLD; localhost is the one named exception below.
        final boolean loopback = LOCALHOST.equals(id) && LOCALHOST.equals(hostOf(publicUrl));
        if (!loopback && (!id.contains(".") || id.startsWith(".") || id.endsWith("."))) {
            throw new IllegalArgumentException("webauthn.relying-party-id is " + relyingPartyId
                    + ", which is not a registrable domain. It needs at least a name and a suffix,"
                    + " e.g. nordtal.eu - a browser refuses a bare TLD, in silence."
                    + " The one exception is localhost, and only when public-url is on it too");
        }
        final String host = hostOf(publicUrl);
        final String lowered = host == null ? "" : host;
        if (!lowered.equals(id) && !lowered.endsWith("." + id)) {
            throw new IllegalArgumentException("webauthn.relying-party-id is " + relyingPartyId
                    + ", which " + host + " is not under. A key can only be registered against the"
                    + " domain the browser is on or a parent of it, so every sign-in would fail in"
                    + " the browser with nothing arriving here. Either make it " + host
                    + " or a parent of it, or correct public-url");
        }
    }

    /** The host of {@code public-url}, lowercased, or {@code null} when it names none. */
    private static @Nullable String hostOf(final String publicUrl) {
        final String host;
        try {
            host = new URI(publicUrl).getHost();
        } catch (URISyntaxException notAUrl) {
            // requirePublicUrl runs first and says this better; reaching here means it did not.
            throw new IllegalArgumentException("public-url is not a URL: " + notAUrl.getMessage());
        }
        return host == null ? null : host.toLowerCase(java.util.Locale.ROOT);
    }
}
