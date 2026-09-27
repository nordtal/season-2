package eu.nordtal.s2.steward.ui.push;

import org.jspecify.annotations.Nullable;

/**
 * What to call a browser in a list of three of them, read from the subscribing request's User-Agent.
 *
 * A push endpoint is an opaque URL, so three of them in a list are three identical prefixes; the
 * User-Agent wins over a typed name since a tap has to stay one gesture. Coarse on purpose: it
 * answers only the platform and browser family from a handful of substrings and gives up honestly
 * on anything else, since nothing depends on the answer being exact.
 */
final class Devices {

    /** The longest User-Agent worth reading. Past this it is not a browser telling the truth. */
    private static final int LONGEST = 512;

    private Devices() {}

    /** A short name for the browser that sent this User-Agent, or null; the interface draws its own words for that. */
    static @Nullable String nameOf(final @Nullable String userAgent) {
        if (userAgent == null || userAgent.isBlank() || userAgent.length() > LONGEST) {
            return null;
        }
        final String platform = platformOf(userAgent);
        final String browser = browserOf(userAgent);
        if (platform == null && browser == null) {
            return null;
        }
        if (platform == null) {
            return browser;
        }
        return browser == null ? platform : platform + ", " + browser;
    }

    private static @Nullable String platformOf(final String userAgent) {
        if (userAgent.contains("iPhone")) {
            return "iPhone";
        }
        if (userAgent.contains("iPad")) {
            return "iPad";
        }
        if (userAgent.contains("Android")) {
            return "Android";
        }
        // Order matters here: earlier checks above already ruled out iPhone, iPad and Android.
        if (userAgent.contains("Windows")) {
            return "Windows";
        }
        if (userAgent.contains("Mac OS X") || userAgent.contains("Macintosh")) {
            return "Mac";
        }
        if (userAgent.contains("Linux")) {
            return "Linux";
        }
        return null;
    }

    private static @Nullable String browserOf(final String userAgent) {
        // Most specific claim first: Chrome, Edge and Opera all also carry "Safari".
        if (userAgent.contains("Edg/")) {
            return "Edge";
        }
        if (userAgent.contains("OPR/")) {
            return "Opera";
        }
        if (userAgent.contains("Firefox/") || userAgent.contains("FxiOS/")) {
            return "Firefox";
        }
        if (userAgent.contains("CriOS/") || userAgent.contains("Chrome/")) {
            return "Chrome";
        }
        if (userAgent.contains("Safari/")) {
            return "Safari";
        }
        return null;
    }
}
