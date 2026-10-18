package eu.nordtal.season.steward.push;

import org.jspecify.annotations.Nullable;

/** Names a browser in a list from the User-Agent of the subscribing request. */
public final class Devices {

    /** The longest User-Agent that is read. */
    private static final int LONGEST = 512;

    private Devices() {}

    /** Returns the system and the browser, each {@code null} when not recognised. */
    static Device of(final @Nullable String userAgent) {
        if (userAgent == null || userAgent.isBlank() || userAgent.length() > LONGEST) {
            return new Device(null, null);
        }
        return new Device(systemOf(userAgent), browserOf(userAgent));
    }

    record Device(@Nullable String system, @Nullable String browser) {}

    private static @Nullable String systemOf(final String userAgent) {
        if (userAgent.contains("iPhone")) {
            return "iPhone";
        }
        if (userAgent.contains("iPad")) {
            return "iPad";
        }
        if (userAgent.contains("Android")) {
            return "Android";
        }
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
        // Chrome, Edge and Opera also contain "Safari", so the order matters.
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
