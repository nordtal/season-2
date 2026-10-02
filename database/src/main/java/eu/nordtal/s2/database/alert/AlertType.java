package eu.nordtal.s2.database.alert;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * What an alert is about, the unit an admin switches on and off per {@link AlertChannel}.
 *
 * The first five are measured by steward and cleared again; the last three are single events.
 */
public enum AlertType {

    /** A service is stopped or reports itself unhealthy, or Docker answered with no services at all. */
    SERVICE(true, false, true),

    /** A kind of backup is missing, or a series is older than the permitted age. */
    BACKUP(true, false, true),

    /** The disk is fuller than its threshold. */
    DISK(true, false, true),

    /** Host memory is more used than its threshold. */
    MEMORY(true, false, true),

    /** A container runs an older image than the registry has, or the registry did not answer. */
    DRIFT(false, false, true),

    /** A run failed. */
    RUN(true, true, false),

    /** A payment needs a look, or a purchase failed. */
    PAYMENT(true, true, false),

    /** The bot could not carry something out in Discord: a role, a direct message or a link. */
    BOT(false, true, false);

    private final boolean push;
    private final boolean discord;
    private final boolean measured;

    AlertType(final boolean push, final boolean discord, final boolean measured) {
        this.push = push;
        this.discord = discord;
        this.measured = measured;
    }

    /** Returns whether an admin who never chose gets this type on {@code channel}. */
    public boolean wantedByDefault(final AlertChannel channel) {
        return switch (channel) {
            case PUSH -> push;
            case DISCORD -> discord;
        };
    }

    /** Returns whether steward measures this type and clears it again, rather than it being one event. */
    public boolean measured() {
        return measured;
    }

    /** Returns the name the browser sends and receives: the constant, lowercased. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Returns the type of a {@link #key()}, or null for a word this build does not know. */
    public static @Nullable AlertType of(final @Nullable String key) {
        for (final AlertType type : values()) {
            if (type.key().equals(key)) {
                return type;
            }
        }
        return null;
    }
}
