package eu.nordtal.s2.steward.ui.push;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * What a push notification can be about - the unit an account switches on and off.
 *
 * Cut the way an admin would name them, not the way the code counts them: {@link #SERVICE} and
 * {@link #BACKUP} are on by default as the two critical cases, {@link #DISK} and {@link #MEMORY}
 * as the two configured percentages an admin would want separately, and {@link #DRIFT} off, since
 * a drifted image is an errand for later rather than something broken now. The default lives here
 * and in no row, since Steward has no accounts table to hang a written-in default on.
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

    /** Whether an account that has never opened the dialog gets this one. See the class note. */
    public boolean enabledByDefault() {
        return enabledByDefault;
    }

    /** The name this type is stored and sent under - the enum constant, lowercased. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The type of a {@link #key()}, or null for a word this version does not know.
     *
     * Null rather than an exception: a row naming a type a later release removed is data, not a
     * fault, and a background poll that threw on it would stop pushing anything at all.
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
