package eu.nordtal.s2.proxy.config;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.ConfigValidator;
import eu.nordtal.jcore.config.exception.ConfigException;
import eu.nordtal.s2.common.command.CommandAllowlist;
import eu.nordtal.s2.common.config.EnvOverrideFile;
import eu.nordtal.s2.common.message.Tone;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;

/**
 * Where {@code proxy}'s config files live, and every rule about what a valid value is.
 *
 * Same shape as {@code access-bot}'s {@code Configs}: each file gets its own environment
 * namespace, and every check runs once at startup rather than being discovered mid-login. A
 * Velocity plugin has no {@code getDataFolder()} the way a Paper plugin does, so the directory is
 * handed in by the caller - Velocity injects it as {@code @DataDirectory Path}, which is
 * {@code plugins/proxy/} for a normal install.
 */
public final class Configs {

    /** A SHA-1 as the pack's own {@code .sha1} file writes it: 40 hex characters, no prefix. */
    private static final Pattern SHA1 = Pattern.compile("[0-9a-fA-F]{40}");

    private Configs() {}

    public static ConfigHandle<DatabaseSpec> database(final Path directory, final Logger logger)
            throws ConfigException {
        return load(directory, logger, "database", DatabaseSpec.class, "NORDTAL_PROXY_DATABASE", config -> {
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

    public static ConfigHandle<GateSpec> gate(final Path directory, final Logger logger) throws ConfigException {
        return load(directory, logger, "gate", GateSpec.class, "NORDTAL_PROXY_GATE", config -> {
            requirePositive("link-code-ttl-minutes", config.linkCodeTtlMinutes());
            requirePositive("fallback-cache-window-minutes", config.fallbackCacheWindowMinutes());
            requirePositive("expiry-check-interval-seconds", config.expiryCheckIntervalSeconds());
            requirePositive("expiry-warning-lead-minutes", config.expiryWarningLeadMinutes());
            requirePositive("phase-poll-interval-seconds", config.phasePollIntervalSeconds());
            requirePositive("playtime-flush-interval-seconds", config.playtimeFlushIntervalSeconds());
            requirePositive("limbo-sweep-interval-seconds", config.limboSweepIntervalSeconds());
            // Whether the name matches a server velocity.toml registers is checked later, in PhaseRouting.
            requireText("server-limbo", config.serverLimbo());
            requireText("server-hunger-games", config.serverHungerGames());
            requireText("server-smp", config.serverSmp());
        });
    }

    /**
     * {@code network.yml} - the MOTD and the one player limit.
     *
     * There is nothing to cross-check here: {@code max-players} is written into the backends from
     * the same {@code .env} variable this file is overridden with, so nothing can drift and nothing
     * needs guarding. A {@code network.yml} in a volume that still carries {@code backend-limit}
     * stops the proxy with that key named, which is jcore's ordinary strict behaviour and the right
     * one here: the key meant something, and a file still carrying it is a deployment that has not
     * been told.
     */
    public static ConfigHandle<NetworkSpec> network(final Path directory, final Logger logger) throws ConfigException {
        return load(directory, logger, "network", NetworkSpec.class, "NORDTAL_PROXY_NETWORK", config -> {
            requirePositive("max-players", config.maxPlayers());
            requirePositive("snapshot-refresh-seconds", config.snapshotRefreshSeconds());
            if (config.commandAllowlist() == null) {
                throw new IllegalArgumentException("command-allowlist is missing; an absent list is"
                        + " not the same as an empty one and this proxy will not guess which was"
                        + " meant");
            }
            for (final String entry : config.commandAllowlist()) {
                // A blank line is dropped silently: the file looks like ten entries, the network acts on nine.
                if (entry == null || entry.isBlank()) {
                    throw new IllegalArgumentException("command-allowlist has a blank entry. Delete"
                            + " the line rather than emptying it - an empty one allows nothing and"
                            + " would be silently ignored");
                }
                // CommandAllowlist#parse strips a leading slash and namespace, so "/" also normalises to empty.
                if (!CommandAllowlist.names(entry)) {
                    throw new IllegalArgumentException("command-allowlist entry '" + entry + "' is"
                            + " nothing once the leading slash and namespace are taken off, so it"
                            + " allows no command at all. Write the command's path, like"
                            + " 'smp status'");
                }
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

    public static ConfigHandle<PackSpec> pack(final Path directory, final Logger logger) throws ConfigException {
        return load(directory, logger, "pack", PackSpec.class, "NORDTAL_PROXY_PACK", config -> {
            requirePositive("apply-timeout-seconds", config.applyTimeoutSeconds());
            if (!config.enabled()) {
                // Deliberately nothing else checked: refusing to start over an unused value defeats the escape hatch.
                return;
            }
            requireText("url", config.url());
            if (!config.url().startsWith("http://") && !config.url().startsWith("https://")) {
                throw new IllegalArgumentException(
                        "url must be an http(s) URL the Minecraft client can download from, was '" + config.url()
                                + "'");
            }
            requireText("sha1", config.sha1());
            if (!SHA1.matcher(config.sha1()).matches()) {
                // Length and alphabet only: whether it is the zip's real hash is a question only the client answers.
                throw new IllegalArgumentException(
                        "sha1 must be the 40 hex characters of the pack zip's SHA-1 - the content of"
                                + " the .sha1 file next to the release asset - was '" + config.sha1() + "'");
            }
        });
    }

    /**
     * Loads the tone colours.
     *
     * No validator: {@code ToneColours#parse} corrects a bad hex value where it parses it, so a typo here is never a
     * reason to refuse a login. Read once at proxy start - see {@code ColoursSpec}'s own javadoc for why this file has
     * no reload path.
     */
    public static ConfigHandle<ColoursSpec> colours(final Path directory, final Logger logger) throws ConfigException {
        return load(directory, logger, "colours", ColoursSpec.class, "NORDTAL_PROXY_COLOURS", config -> {});
    }

    /**
     * {@code ColoursSpec}'s five accessors, as the map {@code ToneColours} parses.
     *
     * An exhaustive {@code switch} with no {@code default}, so a sixth {@link Tone} stops this compiling rather than
     * silently leaving it unpainted.
     */
    public static Map<Tone, String> declared(final ColoursSpec spec) {
        final Map<Tone, String> declared = new EnumMap<>(Tone.class);
        for (final Tone tone : Tone.values()) {
            declared.put(
                    tone,
                    switch (tone) {
                        case GOOD -> spec.good();
                        case BAD -> spec.bad();
                        case WARN -> spec.warn();
                        case NEUTRAL -> spec.neutral();
                        case MUTED -> spec.muted();
                    });
        }
        return declared;
    }

    private static <T> ConfigHandle<T> load(
            final Path directory,
            final Logger logger,
            final String name,
            final Class<T> specType,
            final String envPrefix,
            final ConfigValidator<T> validator)
            throws ConfigException {
        final Path file = directory.resolve(name + ".yml");
        final boolean fresh = !Files.isRegularFile(file);

        final ConfigHandle<T> handle = ConfigLoader.builder(file, specType)
                .envPrefix(envPrefix)
                .validator(validator)
                .load();

        if (fresh) {
            logger.warn(
                    "No config existed at {} - defaults were written and are almost certainly " + "not what you want",
                    file.toAbsolutePath());
        }
        recordEnvironmentOverrides(handle, logger);
        return handle;
    }

    /**
     * Writes {@code handle}'s {@link ConfigHandle#environmentOverrides()} next to its file.
     *
     * So steward-worker can warn that editing an overridden setting there has no effect until the variable is
     * removed. Best-effort: a UI nicety, not a reason for a correctly loaded config to refuse to start the proxy.
     */
    private static void recordEnvironmentOverrides(final ConfigHandle<?> handle, final Logger logger) {
        try {
            EnvOverrideFile.write(handle.file(), handle.environmentOverrides());
        } catch (final IOException e) {
            logger.warn("Could not write the environment-override marker beside {}: {}", handle.file(), e.getMessage());
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
}
