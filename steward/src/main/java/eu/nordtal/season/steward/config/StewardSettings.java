package eu.nordtal.season.steward.config;

import eu.nordtal.season.database.setting.SettingStore;
import eu.nordtal.season.settings.Checks;
import eu.nordtal.season.settings.DatabasePool;
import eu.nordtal.season.settings.DatabaseSettings;
import eu.nordtal.season.settings.DatabaseSpec;
import eu.nordtal.season.settings.Environment;
import eu.nordtal.season.settings.EnvironmentSettings;
import eu.nordtal.season.settings.Group;
import eu.nordtal.season.settings.Setting;
import eu.nordtal.season.settings.Settings;
import eu.nordtal.season.settings.SettingsException;
import java.net.URI;
import java.net.URISyntaxException;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** Holds the settings of Steward. Each value is checked once at load, so a bad value names its key and group at startup. */
public final class StewardSettings {

    public static final String SERVICE = "steward";

    /** {@code NORDTAL_STEWARD} for the steward group, else {@code NORDTAL_STEWARD_<GROUP>}. */
    public static final Environment ENVIRONMENT =
            Environment.of("NORDTAL_STEWARD").withMain("steward");

    private static final String LOCALHOST = "localhost";

    private StewardSettings() {}

    public static Group<StewardSpec> stewardGroup() {
        return Group.of("steward", StewardSpec.class)
                .checkedBy(StewardSettings::checkSteward)
                .whileRunning();
    }

    public static Setting<StewardSpec> steward(final Settings settings) throws SettingsException {
        return settings.load(stewardGroup());
    }

    /** Returns the alerts group. A change applies at the next reading of the monitor. */
    public static Group<AlertsSpec> alertsGroup() {
        return Group.of("alerts", AlertsSpec.class)
                .checkedBy(StewardSettings::checkAlerts)
                .whileRunning();
    }

    public static Setting<AlertsSpec> alerts(final Settings settings) throws SettingsException {
        return settings.load(alertsGroup());
    }

    public static Setting<WebSpec> web(final Settings settings) throws SettingsException {
        return settings.load(Group.of("web", WebSpec.class).checkedBy(StewardSettings::checkWeb));
    }

    public static Setting<DatabaseSpec> database() throws SettingsException {
        return EnvironmentSettings.of(ENVIRONMENT)
                .load(Group.of("database", DatabaseSpec.class).checkedBy(StewardSettings::checkDatabase));
    }

    public static DatabaseSettings stored(final DataSource dataSource, final Logger logger) {
        return DatabaseSettings.over(SettingStore.using(dataSource), SERVICE, ENVIRONMENT, logger);
    }

    private static void checkSteward(final StewardSpec config) {
        Checks.requirePositive("http-timeout-seconds", config.httpTimeoutSeconds());
        requireBunq(config.bunq());
    }

    private static void checkWeb(final WebSpec config) {
        Checks.requirePositive("port", config.port());
        Checks.requirePositive("session-days", config.sessionDays());
        // The ceiling keeps Max-Age within an int.
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
            throw new IllegalArgumentException("password is empty."
                    + " NORDTAL_STEWARD_DATABASE_PASSWORD is what compose.yml sets"
                    + " from POSTGRES_PASSWORD; an empty one means the variable did not"
                    + " reach this container");
        }
        Checks.requirePositive("maximum-pool-size", config.maximumPoolSize());
    }

    /** steward-bunq checks the credentials. */
    private static void requireBunq(final StewardSpec.BunqSpec bunq) {
        Checks.requirePositive("bunq.poll-interval-seconds", bunq.pollIntervalSeconds());
        Checks.requirePositive("bunq.recent-payment-count", bunq.recentPaymentCount());

        // An empty watermark is valid.
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

    /** Checks the relying party ID against {@code publicUrl}. A browser refuses a mismatch without a message. */
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
        // Two labels at least, to refuse a bare TLD. Localhost is the only exception.
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

    private static @Nullable String hostOf(final String publicUrl) {
        final String host;
        try {
            host = new URI(publicUrl).getHost();
        } catch (URISyntaxException notAUrl) {
            // requirePublicUrl runs first and reports this.
            throw new IllegalArgumentException("public-url is not a URL: " + notAUrl.getMessage());
        }
        return host == null ? null : host.toLowerCase(java.util.Locale.ROOT);
    }
}
