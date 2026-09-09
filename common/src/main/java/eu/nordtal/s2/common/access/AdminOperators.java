package eu.nordtal.s2.common.access;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An admin is an operator, on every Paper server, for as long as they are an admin and no longer.
 *
 * <p>Operator rather than a list of permission nodes, because a list only ever covers what somebody
 * wrote down and every plugin added later brings nodes nobody adds to it.
 *
 * <p>{@code setOp} is persistent - Bukkit writes {@code ops.json} - so an operator outlives the
 * session that was given one, and a server that stops between a join and a quit would leave one
 * behind forever. {@link #sweep()} therefore removes <b>every</b> operator at plugin enable,
 * unconditionally and without asking the database, so there is no wrong answer to give when the
 * database is unreachable. The price is that a hand-set console {@code op} does not survive a
 * restart, which is intended: {@code discord_user.admin} is the only admin list.
 *
 * <p>Transitions are tracked rather than re-applied, because every {@code setOp} is a disk write and
 * {@link #refresh} is meant to be called on every notification and poll tick.
 *
 * <p>It never decides <em>who</em> is an admin - the flag is handed in - and it holds no Bukkit
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

    /**
     * Whom <em>this</em> object has opped, so a repeated call is free. Not a source of truth about
     * who is an admin: {@link #sweep()} empties it at enable, and a disagreement with
     * {@code ops.json} is settled by the next start's sweep removing both.
     */
    private final Set<UUID> opped = ConcurrentHashMap.newKeySet();

    public AdminOperators(final Ops ops) {
        this.ops = ops;
    }

    /**
     * Remove operator from everybody, unconditionally. Call once, at plugin enable, before any join
     * can be processed - it is what makes operator a property of the session rather than the disk.
     */
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

    /**
     * A player left. Removes operator if this object granted it, without consulting the admin flag -
     * that may have changed while they were online.
     */
    public void onQuit(final UUID player) {
        set(player, false);
    }

    /**
     * Re-derive operator for everybody online from the authoritative admin set, for the
     * {@code nordtal_admin} watcher. Players who are not online are not touched: a quit already
     * removed what they held. Only a change reaches {@link Ops#setOp}, so an unchanged tick writes
     * nothing to disk.
     *
     * @param admins the full admin set, freshly read - never a delta
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
