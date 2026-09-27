package eu.nordtal.s2.steward.ui.config;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.s2.common.config.EnvOverrideFile;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The two files this service reads, and what has to be true of them before it starts.
 *
 * Validation happens at load, not at first use, so a bad value names the key and the file at
 * startup rather than failing on the first click.
 */
public final class Configs {

    private Configs() {}

    public static ConfigHandle<UiSpec> ui(final Path directory, final Logger logger) throws ConfigException {
        final Path file = directory.resolve("steward-ui.yml");
        final boolean fresh = !Files.isRegularFile(file);

        final ConfigHandle<UiSpec> handle = ConfigLoader.builder(file, UiSpec.class)
                .envPrefix("NORDTAL_STEWARD_UI")
                .validator(config -> {
                    requirePositive("port", config.port());
                    requirePositive("session-days", config.sessionDays());
                    // A ceiling too: multiplied into seconds for a cookie's Max-Age, which is an int.
                    if (config.sessionDays() > 365) {
                        throw new IllegalArgumentException("session-days is " + config.sessionDays()
                                + " - a session lasting longer than a season is not a session");
                    }
                    requirePublicUrl(config.publicUrl());
                    requireRelyingParty(
                            config.webauthn() == null ? null : config.webauthn().relyingPartyId(), config.publicUrl());
                    if (config.worker() == null || config.worker().baseUrl().isBlank()) {
                        throw new IllegalArgumentException("worker.base-url is empty");
                    }
                })
                .load();

        if (fresh) {
            logger.info(
                    "No config existed at {} - it was written with this project's defaults."
                            + " The two secrets are environment variables and are not in it.",
                    file.toAbsolutePath());
        }
        recordEnvironmentOverrides(handle, logger);
        return handle;
    }

    public static ConfigHandle<DatabaseSpec> database(final Path directory, final Logger logger)
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
                    if (config.password() == null || config.password().isBlank()) {
                        // Refused here rather than three seconds later as a pool that cannot connect.
                        throw new IllegalArgumentException("password is empty."
                                + " NORDTAL_STEWARD_UI_DATABASE_PASSWORD is what compose.yml sets"
                                + " from POSTGRES_PASSWORD; an empty one means the variable did not"
                                + " reach this container");
                    }
                    requirePositive("maximum-pool-size", config.maximumPoolSize());
                })
                .load();

        if (fresh) {
            logger.warn(
                    "No database config existed at {} - defaults were written, and localhost"
                            + " is not where the database is from inside a container",
                    file.toAbsolutePath());
        }
        recordEnvironmentOverrides(handle, logger);
        return handle;
    }

    /**
     * Writes {@code handle}'s {@link ConfigHandle#environmentOverrides()} next to its file.
     *
     * Best-effort: not a reason for a correctly loaded config to refuse to start the service.
     */
    private static void recordEnvironmentOverrides(final ConfigHandle<?> handle, final Logger logger) {
        try {
            EnvOverrideFile.write(handle.file(), handle.environmentOverrides());
        } catch (final IOException e) {
            logger.warn("Could not write the environment-override marker beside {}: {}", handle.file(), e.getMessage());
        }
    }

    /**
     * The one address this interface answers on, and Discord's redirect URI is built from it.
     *
     * Parsed rather than pattern-matched, since Discord compares the redirect URI as a string and
     * refuses anything a query or a fragment would turn it into.
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

    /**
     * The domain a security key is bound to, held against the address the browser actually uses.
     *
     * Checked at startup rather than left to the browser: a mismatched relying party id otherwise
     * fails silently as a {@code SecurityError} the browser never reports back here.
     */
    private static final String LOCALHOST = "localhost";

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

    private static void requirePositive(final String key, final int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be greater than zero, not " + value);
        }
    }
}
