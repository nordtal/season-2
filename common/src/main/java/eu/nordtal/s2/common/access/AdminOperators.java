package eu.nordtal.s2.common.access;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An admin is an operator, on every Paper server, for as long as they are an admin and no longer.
 *
 * Operator rather than a list of permission nodes, because a list only ever covers what somebody
 * wrote down and every plugin added later brings nodes nobody adds to it.
 *
 * {@code setOp} is persistent - Bukkit writes {@code ops.json} - so an operator outlives the
 * session that was given one, and a server that stops between a join and a quit would leave one
 * behind forever. {@link #sweep()} therefore removes <b>every</b> operator at plugin enable,
 * unconditionally and without asking the database, so there is no wrong answer to give when the
 * database is unreachable. The price is that a hand-set console {@code op} does not survive a
 * restart, which is intended: {@code discord_user.admin} is the only admin list.
 *
 * Transitions are tracked rather than re-applied, because every {@code setOp} is a disk write and
 * {@link #refresh} is meant to be called on every notification and poll tick.
 *
 * It never decides <em>who</em> is an admin - the flag is handed in - and it holds no Bukkit
 * type, because {@code :common} is compiled against no platform; the two calls that need one arrive
 * through {@link Ops}.
 */
public final class AdminOperators {

    /** The two operations this needs from a platform, satisfied inline by each Paper plugin. */
    public interface Ops {

        /** Grant or remove operator. Persistent: Bukkit writes {@code ops.json}. */
        void setOp(UUID player, boolean operator);

        /** Everybody currently carrying operator, whether online or not. */
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
     * A player joined. Grants operator if they are an admin, and does nothing at all if not.
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
     *
     * Only a change reaches {@link Ops#setOp}, so an unchanged tick writes nothing to disk.
     *
     * @param admins the full admin set, freshly read, never a delta
     * @param online who is currently connected
     */
    public void refresh(final Set<UUID> admins, final Set<UUID> online) {
        for (final UUID player : online) {
            set(player, admins.contains(player));
        }
    }

    /** Whether this object currently holds {@code player} as an operator it granted. */
    public boolean holds(final UUID player) {
        return opped.contains(player);
    }

    /** Whom this object has opped. A copy; for tests and for a status line. */
    public Set<UUID> held() {
        return Set.copyOf(new HashSet<>(opped));
    }

    /** The one place that writes, and the one place that keeps the two in step. */
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
