package eu.nordtal.season.database.alert;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** A way to reach an admin besides Steward's list. */
public enum AlertChannel {

    /** A notification in every browser the admin subscribed. */
    PUSH,

    /** A mention of the admin under the alert's post in the admin channel. */
    DISCORD;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static @Nullable AlertChannel of(final @Nullable String key) {
        for (final AlertChannel channel : values()) {
            if (channel.key().equals(key)) {
                return channel;
            }
        }
        return null;
    }
}
