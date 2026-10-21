package eu.nordtal.season.database.alert;

import eu.nordtal.season.database.audit.JournalAction;
import java.util.Arrays;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * What an alert is about. An admin switches each type on or off per {@link AlertChannel}.
 *
 * Steward measures and clears the first six types. The last three are single events.
 */
public enum AlertType {

    /** A service is stopped or unhealthy, or Docker lists no services. */
    SERVICE(true, false, true),

    /** A backup kind is missing or a series is older than allowed. */
    BACKUP(true, false, true),

    /** The disk is fuller than its threshold. */
    DISK(true, false, true),

    /** Host memory use is above its threshold. */
    MEMORY(true, false, true),

    /** A container runs an older image than the registry has, or the registry did not answer. */
    DRIFT(false, false, true),

    /** The resource pack that the proxy offers does not answer. */
    PACK(true, true, true),

    /** A run failed. */
    RUN(true, true, false),

    /** A payment needs review or a purchase failed. */
    PAYMENT(true, true, false),

    /** The bot could not do something in Discord: a role, a direct message or a link. */
    BOT(false, true, false);

    private final boolean push;
    private final boolean discord;
    private final boolean measured;

    AlertType(final boolean push, final boolean discord, final boolean measured) {
        this.push = push;
        this.discord = discord;
        this.measured = measured;
    }

    /** Returns whether this type is on for {@code channel} by default. */
    public boolean wantedByDefault(final AlertChannel channel) {
        return switch (channel) {
            case PUSH -> push;
            case DISCORD -> discord;
        };
    }

    /** Returns whether steward measures and clears this type. */
    public boolean measured() {
        return measured;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Returns the type of an alert's action, null for an action that is no alert. */
    public static @Nullable AlertType of(final JournalAction action) {
        return switch (action) {
            case SERVICE_DOWN -> SERVICE;
            case BACKUP_STALE -> BACKUP;
            case DISK_FULL -> DISK;
            case MEMORY_FULL -> MEMORY;
            case IMAGE_DRIFT -> DRIFT;
            case PACK_DEAD -> PACK;
            case RUN_FAILED -> RUN;
            case PAYMENT_NEEDS_LOOK, LINK_REFUSED, PURCHASE_FAILED -> PAYMENT;
            case LINK_FAILED,
                    DM_NOT_DELIVERED,
                    ROLE_NOT_CHANGED,
                    ROLE_AMBIGUOUS,
                    ROLE_NOT_CREATED,
                    LOCK_NOT_KEPT,
                    LOCK_WITHOUT_CHANNEL -> BOT;
            default -> null;
        };
    }

    /** Returns the first action of this type, the one a measured type raises and clears with. */
    public JournalAction action() {
        return Arrays.stream(JournalAction.values())
                .filter(action -> of(action) == this)
                .findFirst()
                .orElseThrow();
    }

    public static @Nullable AlertType of(final @Nullable String key) {
        for (final AlertType type : values()) {
            if (type.key().equals(key)) {
                return type;
            }
        }
        return null;
    }
}
