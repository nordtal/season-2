package eu.nordtal.season.smp.capture;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared per-admin active-session guard across all four Steward-to-SMP in-game capture modes.
 * Only one capture mode may be active for an admin at a time.
 */
public final class CaptureSessions {

    private final Map<UUID, CaptureMode> active = new ConcurrentHashMap<>();

    /** Claims an active capture slot for {@code admin}, returning false when another capture is already active. */
    public boolean tryBegin(final UUID admin, final CaptureMode mode) {
        Objects.requireNonNull(admin, "admin");
        Objects.requireNonNull(mode, "mode");
        return active.putIfAbsent(admin, mode) == null;
    }

    /** Releases the active capture slot for {@code admin} if it is held by {@code mode}. */
    public void end(final UUID admin, final CaptureMode mode) {
        Objects.requireNonNull(admin, "admin");
        Objects.requireNonNull(mode, "mode");
        active.remove(admin, mode);
    }

    /** Returns whether {@code admin} currently holds any active capture session. */
    public boolean isActive(final UUID admin) {
        return active.containsKey(Objects.requireNonNull(admin, "admin"));
    }

    /** Returns the mode currently held by {@code admin}, or empty when idle. */
    public Optional<CaptureMode> activeMode(final UUID admin) {
        return Optional.ofNullable(active.get(Objects.requireNonNull(admin, "admin")));
    }
}
