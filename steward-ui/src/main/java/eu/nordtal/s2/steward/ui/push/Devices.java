package eu.nordtal.s2.steward.ui.push;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What to call a browser in a list of three of them (steward/98, Till's review of 2026-09-18).
 *
 * <h2>The endpoint is not a name</h2>
 * A {@code PushSubscription}'s endpoint is a push service's own URL - a hundred-odd characters of
 * {@code fcm.googleapis.com} or {@code web.push.apple.com} and an opaque id - and three of them in
 * a list are three identical prefixes. Whatever names these rows has to come from somewhere, and
 * there are only two candidates: ask the person to type one, or read the User-Agent of the request
 * that subscribed.
 *
 * <p><b>The User-Agent wins, and the trade is written down rather than assumed.</b> A typed name is
 * better - it can say "the phone in my pocket" - but it is a second step between a tap and a working
 * notification, on the one screen where the tap has to be a single genuine user gesture (see
 * {@code lib/push.ts}). A name nobody has to type is a name that is always there. The cost is that
 * two iPhones in the same household read "iPhone, Safari" twice, and the list shows the date each
 * was added and marks the one being read from, which is enough to tell them apart. Renaming a device
 * is deliberately not built: it is a field with no second use, and the day somebody actually cannot
 * tell two rows apart is the day to add it.</p>
 *
 * <h2>Coarse on purpose</h2>
 * This is not user-agent parsing in the general sense and must not grow into it: it answers the
 * platform and the browser family, both taken from the handful of substrings that have been stable
 * for a decade, and gives up honestly on anything else. A User-Agent is self-reported and every
 * browser lies in it somewhere; a list entry is allowed to be approximate, and nothing at all
 * depends on the answer.
 */
final class Devices {

    /** The longest User-Agent worth reading. Past this it is not a browser telling the truth. */
    private static final int LONGEST = 512;

    private Devices() {
    }

    /**
     * A short name for the browser that sent this User-Agent, or null when there is nothing to say.
     *
     * <p>Null rather than "Unknown": the interface draws its own words for a device it cannot name,
     * and a row holding the English word "Unknown" would be a value that looks like a name in every
     * place that only reads the column.</p>
     */
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

    private static @Nullable String platformOf(final @NotNull String userAgent) {
        if (userAgent.contains("iPhone")) {
            return "iPhone";
        }
        if (userAgent.contains("iPad")) {
            return "iPad";
        }
        if (userAgent.contains("Android")) {
            return "Android";
        }
        // Order matters here and nowhere else in this file: every Windows and Android User-Agent
        // also carries "Mozilla", and macOS's carries "Mac OS X" inside a longer parenthesis.
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

    private static @Nullable String browserOf(final @NotNull String userAgent) {
        // Chrome's own User-Agent contains "Safari", Edge's contains both "Chrome" and "Safari",
        // and Opera's contains all three. So this reads the most specific claim first and stops.
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
