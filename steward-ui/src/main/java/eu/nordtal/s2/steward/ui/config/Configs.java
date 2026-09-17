package eu.nordtal.s2.steward.ui.config;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.s2.common.config.EnvOverrideFile;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
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
                    requirePositive("session-days", config.sessionDays());
                    // A ceiling as well as a floor, because this one is multiplied into seconds
                    // and put in a cookie's Max-Age, which is an int. A year is already far past
                    // anything defensible; the point of the bound is that a typo says so instead
                    // of overflowing into a cookie the browser drops.
                    if (config.sessionDays() > 365) {
                        throw new IllegalArgumentException("session-days is " + config.sessionDays()
                                + " - a session lasting longer than a season is not a session");
                    }
                    requirePublicUrl(config.publicUrl());
                    requireRelyingParty(config.webauthn() == null
                            ? null : config.webauthn().relyingPartyId(), config.publicUrl());
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
        recordEnvironmentOverrides(handle, logger);
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
                    if (config.password() == null || config.password().isBlank()) {
                        // The default is the empty string, and an empty password is not a password
                        // this deployment ever uses - it is the variable that did not arrive.
                        // Refused here rather than three seconds later as a pool that cannot
                        // connect, which is the same fault wearing a stack trace.
                        throw new IllegalArgumentException("password is empty."
                                + " NORDTAL_STEWARD_UI_DATABASE_PASSWORD is what compose.yml sets"
                                + " from POSTGRES_PASSWORD; an empty one means the variable did not"
                                + " reach this container");
                    }
                    requirePositive("maximum-pool-size", config.maximumPoolSize());
                })
                .load();

        if (fresh) {
            logger.warn("No database config existed at {} - defaults were written, and localhost"
                    + " is not where the database is from inside a container",
                    file.toAbsolutePath());
        }
        recordEnvironmentOverrides(handle, logger);
        return handle;
    }

    /**
     * Writes {@code handle}'s {@link ConfigHandle#environmentOverrides()} next to its file, so
     * steward-worker can warn that editing an overridden setting there has no effect until the
     * variable is removed (steward/76). Best-effort: this is a UI nicety, not a reason for a
     * correctly loaded config to refuse to start the service.
     */
    private static void recordEnvironmentOverrides(final ConfigHandle<?> handle, final Logger logger) {
        try {
            EnvOverrideFile.write(handle.file(), handle.environmentOverrides());
        } catch (final IOException e) {
            logger.warn("Could not write the environment-override marker beside {}: {}",
                    handle.file(), e.getMessage());
        }
    }

    /**
     * The one address this interface answers on, and Discord's redirect URI is built from it.
     *
     * <h2>Starting with http:// is not the same as being a URL</h2>
     * The check used to be a prefix and a trailing slash, which lets {@code https://} through -
     * scheme, no host - and {@code https://?x=1} with it. Both start the process happily, and the
     * failure arrives later and somewhere else: Discord is handed a redirect URI it cannot match
     * and answers {@code invalid_request}, which reads like a mistake in the Discord application
     * rather than a line in a YAML file on this host.
     *
     * <p>So it is parsed. A query or a fragment is refused for the same reason the trailing slash
     * is: Discord compares the redirect URI as a string, and {@code /auth/callback} appended to
     * something already carrying a {@code ?} is not a URL anybody registered.</p>
     */
    static void requirePublicUrl(final String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("public-url is empty - it is the address this"
                    + " interface answers on, e.g. https://steward.dev.nordtal.eu");
        }
        if (url.endsWith("/")) {
            throw new IllegalArgumentException(
                    "public-url must not end with a slash, or the redirect URI would "
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
            throw new IllegalArgumentException(
                    "public-url must start with http:// or https:// - Discord is given "
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
     * <h2>Why this is checked at startup and not left to the browser</h2>
     * A browser refuses a WebAuthn ceremony whose Relying Party ID is not the origin's domain or a
     * parent of it - and it refuses it <em>in the browser</em>, as a {@code SecurityError} in a
     * promise nobody sees, with no request ever reaching this service. So the symptom of one wrong
     * line in a YAML file would be "the key dialog never opens", on someone else's phone, with
     * nothing in any log on this host. Refused here it is a container that will not start and a
     * sentence naming both values.
     *
     * <p>Neither is it a thing anybody should be relaxed about getting wrong in the other
     * direction: if this were allowed to be <em>broader</em> than the public address's domain -
     * {@code eu}, say - every site under it could ask for these keys. That combination is refused
     * by browsers too, and it is refused here first.</p>
     */
    static void requireRelyingParty(final String relyingPartyId, final String publicUrl) {
        if (relyingPartyId == null || relyingPartyId.isBlank()) {
            throw new IllegalArgumentException("webauthn.relying-party-id is empty - it is the"
                    + " domain every security key is registered against, e.g. nordtal.eu");
        }
        final String id = relyingPartyId.trim().toLowerCase(java.util.Locale.ROOT);
        if (id.contains("/") || id.contains(":")) {
            throw new IllegalArgumentException("webauthn.relying-party-id is a DOMAIN, not a URL"
                    + " - no scheme, no port, no path. Was: " + relyingPartyId);
        }
        // AT LEAST TWO LABELS, which is as far as this check honestly goes. A browser refuses a
        // relying party id that is a public suffix - `eu`, `co.uk`, `github.io` - because every
        // site under one would otherwise share a set of keys. Knowing which strings those are
        // needs the Public Suffix List: a dependency and a data file that goes stale monthly, for
        // a value that is set once and is `nordtal.eu`. So this catches the shape of the mistake -
        // a bare TLD - and leaves the rest to the browser, which refuses it anyway. What it must
        // not do is accept `eu` silently, which is what a plain "is it a suffix of the host" test
        // does: every domain ending in .eu passes that.
        if (!id.contains(".") || id.startsWith(".") || id.endsWith(".")) {
            throw new IllegalArgumentException("webauthn.relying-party-id is " + relyingPartyId
                    + ", which is not a registrable domain. It needs at least a name and a suffix,"
                    + " e.g. nordtal.eu - a browser refuses a bare TLD, in silence");
        }
        final String host;
        try {
            host = new URI(publicUrl).getHost();
        } catch (URISyntaxException notAUrl) {
            // requirePublicUrl runs first and says this better; reaching here means it did not.
            throw new IllegalArgumentException("public-url is not a URL: " + notAUrl.getMessage());
        }
        final String lowered = host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
        if (!lowered.equals(id) && !lowered.endsWith("." + id)) {
            throw new IllegalArgumentException("webauthn.relying-party-id is " + relyingPartyId
                    + ", which " + host + " is not under. A key can only be registered against the"
                    + " domain the browser is on or a parent of it, so every sign-in would fail in"
                    + " the browser with nothing arriving here. Either make it " + host
                    + " or a parent of it, or correct public-url");
        }
    }

    private static void requirePositive(final String key, final int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be greater than zero, not " + value);
        }
    }
}
