package eu.nordtal.s2.steward.ui.push;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * What a push notification can be <b>about</b> - the unit an account switches on and off
 * (steward/98, Till's review of 2026-09-18).
 *
 * <h2>Cut the way an admin names them, not the way the code counts them</h2>
 * {@code health.ts} has five families of trigger and {@code AlertLevel} has three kinds plus three
 * measurements; neither list is a list somebody would tick in a dialog. These five are:
 *
 * <ul>
 *   <li>{@link #SERVICE} - a container is stopped, unhealthy, or the whole list came back empty.
 *       Three code paths, one sentence: "something is not running".</li>
 *   <li>{@link #BACKUP} - a kind of backup is missing <b>or</b> the newest one is older than the
 *       permitted age. Deliberately one type and not two: both are red, both are the same errand,
 *       and "there is no dump" against "the dump is from Tuesday" is a distinction for the page
 *       being linked to, not for a switch.</li>
 *   <li>{@link #DISK} and {@link #MEMORY} - the two configured percentages, one type each, because
 *       they are the two an admin would plausibly want separately (a full disk is an errand; a busy
 *       machine on a Saturday evening is four Minecraft servers doing their job).</li>
 *   <li>{@link #DRIFT} - a container runs an older image than the registry has, or the registry
 *       could not be asked.</li>
 * </ul>
 *
 * <h2>The default is Till's, verbatim: critical cases plus disk and memory are on, the rest is off</h2>
 * (2026-09-18, on the question of whether only critical ones should be.) "Critical" is the two that
 * are red - {@link #SERVICE} and {@link #BACKUP} - and "the rest" is exactly {@link #DRIFT}: nothing
 * is broken when an image has drifted, it is an errand for the next quiet moment, and a lock screen
 * at two in the morning is not that moment.
 *
 * <p><b>The default lives here and in no row.</b> A default written into the database when an
 * account appears would be whichever default was current on the day that account first signed in -
 * and Steward has no accounts table, so there is no such day to hang it on either. See {@code V30}.</p>
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
    public @NotNull String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The type of a {@link #key()}, or null for a word this version does not know.
     *
     * <p>Null rather than an exception: a row in {@code steward_push_preference} naming a type that
     * a later release removed is data, not a fault, and a background poll that threw on it would
     * stop pushing anything at all.</p>
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
