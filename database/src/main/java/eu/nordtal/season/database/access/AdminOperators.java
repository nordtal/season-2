package eu.nordtal.season.database.access;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes an admin an operator on every Paper server for as long as they are an admin.
 * {@code setOp} persists in {@code ops.json}, so {@link #sweep()} removes every operator at enable, a hand-set one too.
 */
public final class AdminOperators {

    /** The two operations this needs from a platform. */
    public interface Ops {

        void setOp(UUID player, boolean operator);

        Set<UUID> operators();
    }

    private final Ops ops;

    /** Whom this object opped, so a repeated call costs nothing. It is no source of truth about admins. */
    private final Set<UUID> opped = ConcurrentHashMap.newKeySet();

    public AdminOperators(final Ops ops) {
        this.ops = ops;
    }

    /** Removes operator from everybody. Call it once at plugin enable, before any join. */
    public void sweep() {
        for (final UUID operator : Set.copyOf(ops.operators())) {
            ops.setOp(operator, false);
        }
        opped.clear();
    }

    /**
     * Grants operator to a joining admin and does nothing otherwise.
     *
     * @param isAdmin the {@code discord_user.admin} flag, read by the caller
     */
    public void onJoin(final UUID player, final boolean isAdmin) {
        set(player, isAdmin);
    }

    /** Removes operator from a leaving player if this object granted it. */
    public void onQuit(final UUID player) {
        set(player, false);
    }

    /**
     * Sets operator for everybody online from the full admin set. Only a change reaches {@link Ops#setOp}.
     *
     * @param admins the full admin set, freshly read, never a delta
     */
    public void refresh(final Set<UUID> admins, final Set<UUID> online) {
        for (final UUID player : online) {
            set(player, admins.contains(player));
        }
    }

    public boolean holds(final UUID player) {
        return opped.contains(player);
    }

    public Set<UUID> held() {
        return Set.copyOf(new HashSet<>(opped));
    }

    private void set(final UUID player, final boolean operator) {
        if (operator) {
            if (opped.add(player)) {
                ops.setOp(player, true);
            }
            return;
        }
        if (opped.remove(player)) {
            ops.setOp(player, false);
        }
    }
}
