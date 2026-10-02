package eu.nordtal.s2.proxy.config;

import eu.nordtal.s2.settings.Checks;
import java.util.regex.Pattern;

/**
 * Every rule about what a valid value of the proxy's settings is.
 *
 * A refused value names its key.
 */
public final class ProxySettings {

    /** A SHA-1 as the pack's {@code .sha1} file writes it: 40 hex characters, no prefix. */
    private static final Pattern SHA1 = Pattern.compile("[0-9a-fA-F]{40}");

    private ProxySettings() {}

    /**
     * Refuses a gate group the proxy cannot run on.
     *
     * @throws IllegalArgumentException naming the first value that is wrong
     */
    public static void checkGate(final GateSpec config) {
        Checks.requirePositive("link-code-ttl-minutes", config.linkCodeTtlMinutes());
        Checks.requirePositive("fallback-cache-window-minutes", config.fallbackCacheWindowMinutes());
        Checks.requirePositive("expiry-check-interval-seconds", config.expiryCheckIntervalSeconds());
        Checks.requirePositive("expiry-warning-lead-minutes", config.expiryWarningLeadMinutes());
        Checks.requirePositive("playtime-flush-interval-seconds", config.playtimeFlushIntervalSeconds());
        Checks.requirePositive("limbo-sweep-interval-seconds", config.limboSweepIntervalSeconds());
        // Whether the name matches a server velocity.toml registers is checked later, in PhaseRouting.
        Checks.requireText("server-limbo", config.serverLimbo());
        Checks.requireText("server-hunger-games", config.serverHungerGames());
        Checks.requireText("server-smp", config.serverSmp());
    }

    /**
     * Refuses a proxy network group the proxy cannot run on.
     *
     * @throws IllegalArgumentException naming the first value that is wrong
     */
    public static void checkNetwork(final NetworkSpec config) {
        Checks.requirePositive("snapshot-refresh-seconds", config.snapshotRefreshSeconds());
    }

    /**
     * Refuses a pack group the proxy cannot run on.
     *
     * @throws IllegalArgumentException naming the first value that is wrong
     */
    public static void checkPack(final PackSpec config) {
        Checks.requirePositive("apply-timeout-seconds", config.applyTimeoutSeconds());
        if (!config.enabled()) {
            // Nothing else is checked: refusing to start over an unused value defeats the escape hatch.
            return;
        }
        Checks.requireText("url", config.url());
        if (!config.url().startsWith("http://") && !config.url().startsWith("https://")) {
            throw new IllegalArgumentException(
                    "url must be an http(s) URL the Minecraft client can download from, was '" + config.url() + "'");
        }
        Checks.requireText("sha1", config.sha1());
        if (!SHA1.matcher(config.sha1()).matches()) {
            // Length and alphabet only: whether it is the zip's real hash is a question only the client answers.
            throw new IllegalArgumentException(
                    "sha1 must be the 40 hex characters of the pack zip's SHA-1 - the content of"
                            + " the .sha1 file next to the release asset - was '" + config.sha1() + "'");
        }
    }
}
