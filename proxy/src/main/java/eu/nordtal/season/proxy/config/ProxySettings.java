package eu.nordtal.season.proxy.config;

import eu.nordtal.season.settings.Checks;
import java.util.regex.Pattern;

/** Checks the settings of the proxy. Each exception names the key. */
public final class ProxySettings {

    /** A SHA-1 of 40 hex characters. */
    private static final Pattern SHA1 = Pattern.compile("[0-9a-fA-F]{40}");

    private ProxySettings() {}

    /** Throws an {@link IllegalArgumentException} that names the first invalid value of the gate group. */
    public static void checkGate(final GateSpec config) {
        Checks.requirePositive("link-code-ttl-minutes", config.linkCodeTtlMinutes());
        Checks.requirePositive("fallback-cache-window-minutes", config.fallbackCacheWindowMinutes());
        Checks.requirePositive("expiry-warning-lead-minutes", config.expiryWarningLeadMinutes());
        Checks.requirePositive("playtime-flush-interval-seconds", config.playtimeFlushIntervalSeconds());
        Checks.requirePositive("limbo-sweep-interval-seconds", config.limboSweepIntervalSeconds());
        Checks.requireText("server-limbo", config.serverLimbo());
        Checks.requireText("server-hunger-games", config.serverHungerGames());
        Checks.requireText("server-smp", config.serverSmp());
    }

    /** Throws an {@link IllegalArgumentException} that names the first invalid value of the pack group. */
    public static void checkPack(final PackSpec config) {
        Checks.requirePositive("apply-timeout-seconds", config.applyTimeoutSeconds());
        if (!config.enabled()) {
            return;
        }
        Checks.requireText("url", config.url());
        if (!config.url().startsWith("http://") && !config.url().startsWith("https://")) {
            throw new IllegalArgumentException(
                    "url must be an http(s) URL the Minecraft client can download from, was '" + config.url() + "'");
        }
        Checks.requireText("sha1", config.sha1());
        if (!SHA1.matcher(config.sha1()).matches()) {
            throw new IllegalArgumentException(
                    "sha1 must be the 40 hex characters of the pack zip's SHA-1 - the content of"
                            + " the .sha1 file next to the release asset - was '" + config.sha1() + "'");
        }
    }
}
