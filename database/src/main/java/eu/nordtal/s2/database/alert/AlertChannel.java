package eu.nordtal.s2.database.alert;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Where an alert can reach an admin besides Steward's own list, which shows every alert. */
public enum AlertChannel {

    /** A notification on every browser the admin subscribed. */
    PUSH,

    /** A mention of the admin under the alert's post in the admin channel. */
    DISCORD;

    /** Returns the name the browser sends and receives: the constant, lowercased. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Returns the channel of a {@link #key()}, or null for a word this build does not know. */
    public static @Nullable AlertChannel of(final @Nullable String key) {
        for (final AlertChannel channel : values()) {
            if (channel.key().equals(key)) {
                return channel;
            }
        }
        return null;
    }
}
