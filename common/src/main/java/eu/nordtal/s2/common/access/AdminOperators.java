package eu.nordtal.s2.common.access;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An admin is an operator on every Paper server, for as long as they are an admin and no longer.
 * {@code setOp} persists in {@code ops.json}, so {@link #sweep()} removes every operator at enable, a hand-set one
 * included.
 */
public final class AdminOperators {

    /** The two operations this needs from a platform, satisfied inline by each Paper plugin. */
    public interface Ops {

        /** Grants or removes operator; Bukkit persists it in {@code ops.json}. */
        void setOp(UUID player, boolean operator);

        /** Returns everybody currently carrying operator, online or not. */
        Set<UUID> operators();
    }

    private final Ops ops;

    /** Whom this object has opped, so a repeated call is free; not a source of truth about admins. */
    private final Set<UUID> opped = ConcurrentHashMap.newKeySet();

    public AdminOperators(final Ops ops) {
        this.ops = ops;
    }

    /** Removes operator from everybody; call once at plugin enable, before any join is processed. */
    public void sweep() {
        for (final UUID operator : Set.copyOf(ops.operators())) {
            ops.setOp(operator, false);
        }
        opped.clear();
    }

    /**
     * Grants operator to a joining player who is an admin, and does nothing otherwise.
     *
     * @param player  who joined
     * @param isAdmin their {@code discord_user.admin} flag, already read by the caller
     */
    public void onJoin(final UUID player, final boolean isAdmin) {
        set(player, isAdmin);
    }

    /** Removes operator from a leaving player if this object granted it, regardless of the admin flag. */
    public void onQuit(final UUID player) {
        set(player, false);
    }

    /**
     * Re-derives operator for everybody online from the full admin set.
     * Only a change reaches {@link Ops#setOp}, so an unchanged tick writes nothing.
     *
     * @param admins the full admin set, freshly read, never a delta
     * @param online who is currently connected
     */
    public void refresh(final Set<UUID> admins, final Set<UUID> online) {
        for (final UUID player : online) {
            set(player, admins.contains(player));
        }
    }

    /** Returns whether this object currently holds {@code player} as an operator it granted. */
    public boolean holds(final UUID player) {
        return opped.contains(player);
    }

    /** Returns a copy of whom this object has opped. */
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
