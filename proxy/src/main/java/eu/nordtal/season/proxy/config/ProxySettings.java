package eu.nordtal.season.proxy.config;

import eu.nordtal.season.settings.Checks;
import java.util.regex.Pattern;

/** Checks the settings of the proxy. Each exception names the key. */
public final class ProxySettings {

    /** A SHA-1 of 40 hex characters. */
    private static final Pattern SHA1 = Pattern.compile("[0-9a-fA-F]{40}");

    private ProxySettings() {}

    /**
     * Throws an {@link IllegalArgumentException} that names the first invalid value of the pack group.
     * The map pack may be left out, with both of its values empty.
     */
    public static void checkPack(final PackSpec config) {
        if (!config.enabled()) {
            return;
        }
        checkUrl("url", config.url());
        checkSha1("sha1", config.sha1());
        if (config.mapUrl().isEmpty() && config.mapSha1().isEmpty()) {
            return;
        }
        checkUrl("map-url", config.mapUrl());
        checkSha1("map-sha1", config.mapSha1());
    }

    private static void checkUrl(final String key, final String url) {
        Checks.requireText(key, url);
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException(
                    key + " must be an http(s) URL the Minecraft client can download from, was '" + url + "'");
        }
    }

    private static void checkSha1(final String key, final String sha1) {
        Checks.requireText(key, sha1);
        if (!SHA1.matcher(sha1).matches()) {
            throw new IllegalArgumentException(
                    key + " must be the 40 hex characters of the pack zip's SHA-1, was '" + sha1 + "'");
        }
    }
}
