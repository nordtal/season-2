package eu.nordtal.s2.steward.ui.push;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * What a push notification can be about, the unit an account switches on and off.
 *
 * {@link #DRIFT} is off by default, since a drifted image is an errand rather than a breakage.
 */
public enum AlertType {

    /** A service is stopped, reports itself unhealthy, or Docker answered with no services at all. */
    SERVICE(true),

    /** A kind of backup is missing, or the most neglected series is older than the permitted age. */
    BACKUP(true),

    /** The disk is fuller than {@code alerts.disk-percent}. */
    DISK(true),

    /** Host memory is more used than {@code alerts.memory-percent}. */
    MEMORY(true),

    /** A container runs an older image than the registry has, or the registry did not answer. */
    DRIFT(false);

    private final boolean enabledByDefault;

    AlertType(final boolean enabledByDefault) {
        this.enabledByDefault = enabledByDefault;
    }

    /** Whether an account that has never opened the dialog gets this one. */
    public boolean enabledByDefault() {
        return enabledByDefault;
    }

    /** The name this type is stored and sent under: the constant, lowercased. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The type of a {@link #key()}, or null for a word this version does not know.
     *
     * Null rather than an exception, since a throwing background poll would stop pushing anything at all.
     */
    public static @Nullable AlertType of(final @Nullable String key) {
        if (key == null) {
            return null;
        }
        for (final AlertType type : values()) {
            if (type.key().equals(key)) {
                return type;
            }
        }
        return null;
    }
}
