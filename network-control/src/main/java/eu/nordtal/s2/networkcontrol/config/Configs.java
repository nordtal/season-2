package eu.nordtal.s2.networkcontrol.config;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.ConfigValidator;
import eu.nordtal.jcore.config.exception.ConfigException;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Where {@code network-control}'s config files live, and every rule about what a valid value is.
 * <p>
 * Same shape as {@code access-bot}'s {@code Configs}: each file gets its own environment
 * namespace, and every check runs once at startup rather than being discovered mid-login. A
 * Velocity plugin has no {@code getDataFolder()} the way a Paper plugin does, so the directory is
 * handed in by the caller - Velocity injects it as {@code @DataDirectory Path}, which is
 * {@code plugins/network-control/} for a normal install.
 * </p>
 */
public final class Configs {

    /** A SHA-1 as the pack's own {@code .sha1} file writes it: 40 hex characters, no prefix. */
    private static final Pattern SHA1 = Pattern.compile("[0-9a-fA-F]{40}");

    private Configs() {
    }

    public static @NotNull ConfigHandle<DatabaseSpec> database(final Path directory, final Logger logger)
            throws ConfigException {
        return load(directory, logger, "database", DatabaseSpec.class, "NORDTAL_NETWORK_CONTROL_DATABASE", config -> {
            requireText("jdbc-url", config.jdbcUrl());
            requireText("username", config.username());
            if (!config.jdbcUrl().startsWith("jdbc:postgresql:")) {
                throw new IllegalArgumentException(
                        "jdbc-url must be a PostgreSQL URL (jdbc:postgresql://host:port/database)");
            }
            requirePositive("maximum-pool-size", config.maximumPoolSize());
            requirePositive("query-timeout-seconds", config.queryTimeoutSeconds());
        });
    }

    public static @NotNull ConfigHandle<GateSpec> gate(final Path directory, final Logger logger)
            throws ConfigException {
        return load(directory, logger, "gate", GateSpec.class, "NORDTAL_NETWORK_CONTROL_GATE", config -> {
            requirePositive("link-code-ttl-minutes", config.linkCodeTtlMinutes());
            requirePositive("fallback-cache-window-minutes", config.fallbackCacheWindowMinutes());
            requirePositive("expiry-check-interval-seconds", config.expiryCheckIntervalSeconds());
            requirePositive("expiry-warning-lead-minutes", config.expiryWarningLeadMinutes());
            requirePositive("phase-poll-interval-seconds", config.phasePollIntervalSeconds());
            requirePositive("playtime-flush-interval-seconds", config.playtimeFlushIntervalSeconds());
            requirePositive("limbo-sweep-interval-seconds", config.limboSweepIntervalSeconds());
            // A blank server name could never resolve, and an empty string is the one value that is
            // certainly a mistake rather than "we call it something else". Whether the name matches
            // a server velocity.toml actually registers is not checkable here - the proxy's server
            // list is not this class's to see - and is handled where it is needed, in PhaseRouting.
            requireText("server-limbo", config.serverLimbo());
            requireText("server-hunger-games", config.serverHungerGames());
            requireText("server-smp", config.serverSmp());
        });
    }

    /**
     * {@code network.yml} - the MOTD and the one player limit.
     * <p>
     * The {@code max-players} against {@code backend-limit} check is the interesting one: it is a
     * value in this file compared against a value that is written into <em>another container's</em>
     * {@code server.properties} by the entrypoint. They are two halves of one decision, and the
     * failure mode of letting them drift is silent - the backends quietly become the network's real
     * limit again, exactly as they were before 2026-09-03. So it stops the proxy rather than
     * warning: a warning in a container log is a thing nobody reads until they are already looking
     * for the cause.
     * </p>
     */
    public static @NotNull ConfigHandle<NetworkSpec> network(final Path directory, final Logger logger)
            throws ConfigException {
        return load(directory, logger, "network", NetworkSpec.class, "NORDTAL_NETWORK_CONTROL_NETWORK", config -> {
            requirePositive("max-players", config.maxPlayers());
            requirePositive("backend-limit", config.backendLimit());
            requirePositive("snapshot-refresh-seconds", config.snapshotRefreshSeconds());
            if (config.maxPlayers() > config.backendLimit()) {
                throw new IllegalArgumentException(
                        "max-players (" + config.maxPlayers() + ") is above backend-limit ("
                                + config.backendLimit() + "), so the Paper backends would refuse players"
                                + " before this proxy does - and they refuse with \"Server full\" after"
                                + " the login gate, the resource pack and the wait in limbo. Raise"
                                + " BACKEND_MAX_PLAYERS in .env to at least max-players, or lower this.");
            }
            final NetworkSpec.MotdSpec motd = config.motd();
            if (motd == null) {
                throw new IllegalArgumentException("motd is missing; it needs one entry per season phase");
            }
            requireText("motd.pre-launch", motd.preLaunch());
            requireText("motd.pre-event", motd.preEvent());
            requireText("motd.start-event", motd.startEvent());
            requireText("motd.smp", motd.smp());
            requireText("motd.maintenance", motd.maintenance());
        });
    }

    public static @NotNull ConfigHandle<PackSpec> pack(final Path directory, final Logger logger)
            throws ConfigException {
        return load(directory, logger, "pack", PackSpec.class, "NORDTAL_NETWORK_CONTROL_PACK", config -> {
            requirePositive("apply-timeout-seconds", config.applyTimeoutSeconds());
            if (!config.enabled()) {
                // Nothing else is checked, deliberately: a proxy running without a pack is allowed
                // to carry a half-filled pack.yml, and refusing to start over a value nothing reads
                // would make the escape hatch harder to use than the thing it escapes.
                return;
            }
            requireText("url", config.url());
            if (!config.url().startsWith("http://") && !config.url().startsWith("https://")) {
                throw new IllegalArgumentException(
                        "url must be an http(s) URL the Minecraft client can download from, was '"
                                + config.url() + "'");
            }
            requireText("sha1", config.sha1());
            if (!SHA1.matcher(config.sha1()).matches()) {
                // The one check that catches the mistake this file exists to prevent: a hash typed
                // by hand, truncated in a copy, or left behind from the previous release. Length
                // and alphabet are all that can be checked here - whether it is the hash of the zip
                // at `url` is a question only the client can answer, and it answers it with
                // FAILED_DOWNLOAD.
                throw new IllegalArgumentException(
                        "sha1 must be the 40 hex characters of the pack zip's SHA-1 - the content of"
                                + " the .sha1 file next to the release asset - was '" + config.sha1() + "'");
            }
        });
    }

    // ------------------------------------------------------------------ loading

    private static <T> ConfigHandle<T> load(final Path directory, final Logger logger, final String name,
                                             final Class<T> specType, final String envPrefix,
                                             final ConfigValidator<T> validator) throws ConfigException {
        final Path file = directory.resolve(name + ".yml");
        final boolean fresh = !Files.isRegularFile(file);

        final ConfigHandle<T> handle = ConfigLoader.builder(file, specType)
                .envPrefix(envPrefix)
                .validator(validator)
                .load();

        if (fresh) {
            logger.warn("No config existed at {} - defaults were written and are almost certainly "
                    + "not what you want", file.toAbsolutePath());
        }
        return handle;
    }

    // ------------------------------------------------------------------ validation helpers

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
}
