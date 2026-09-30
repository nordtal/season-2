package eu.nordtal.s2.steward.ui.config;

import eu.nordtal.s2.settings.Checks;
import eu.nordtal.s2.settings.DatabasePool;
import eu.nordtal.s2.settings.DatabaseSpec;
import eu.nordtal.s2.settings.FileSettings;
import eu.nordtal.s2.settings.Setting;
import eu.nordtal.s2.settings.Settings;
import eu.nordtal.s2.settings.SettingsException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** The two files this service reads, validated at load so a bad value names its key and file at startup. */
public final class UiSettings {

    private UiSettings() {}

    /** Loads {@code steward-ui.yml}; the two secrets are environment variables and are not in it. */
    public static Setting<UiSpec> ui(final Path directory, final Logger logger) throws SettingsException {
        return settings(directory, logger).load("steward-ui", UiSpec.class, UiSettings::checkUi);
    }

    /** Loads {@code database.yml}, refusing an empty password rather than a pool that cannot connect. */
    public static Setting<DatabaseSpec> database(final Path directory, final Logger logger) throws SettingsException {
        return settings(directory, logger).load("database", DatabaseSpec.class, UiSettings::checkDatabase);
    }

    private static void checkUi(final UiSpec config) {
        Checks.requirePositive("port", config.port());
        Checks.requirePositive("session-days", config.sessionDays());
        // A ceiling too: it is multiplied into a cookie's Max-Age, which is an int.
        if (config.sessionDays() > 365) {
            throw new IllegalArgumentException("session-days is " + config.sessionDays()
                    + " - a session lasting longer than a season is not a session");
        }
        requirePublicUrl(config.publicUrl());
        requireRelyingParty(config.webauthn() == null ? null : config.webauthn().relyingPartyId(), config.publicUrl());
        if (config.worker() == null || config.worker().baseUrl().isBlank()) {
            throw new IllegalArgumentException("worker.base-url is empty");
        }
    }

    private static void checkDatabase(final DatabaseSpec config) {
        DatabasePool.check(config);
        if (config.jdbcUrl() == null || !config.jdbcUrl().startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("jdbc-url must be a PostgreSQL URL (jdbc:postgresql://host:port/db)");
        }
        if (config.password() == null || config.password().isBlank()) {
            // Refused here rather than three seconds later as a pool that cannot connect.
            throw new IllegalArgumentException("password is empty."
                    + " NORDTAL_STEWARD_UI_DATABASE_PASSWORD is what compose.yml sets"
                    + " from POSTGRES_PASSWORD; an empty one means the variable did not"
                    + " reach this container");
        }
        Checks.requirePositive("maximum-pool-size", config.maximumPoolSize());
    }

    private static Settings settings(final Path directory, final Logger logger) {
        return FileSettings.in(directory, "NORDTAL_STEWARD_UI", "steward-ui", logger);
    }

    /**
     * The address this interface answers on, parsed since Discord compares the redirect URI built from it as a string.
     */
    static void requirePublicUrl(final String url) {
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

    private static final String LOCALHOST = "localhost";

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
