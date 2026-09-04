package eu.nordtal.s2.common.access;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An admin is an operator, on every Paper server, for as long as they are an admin and no longer.
 *
 * <h2>Why operator rather than a list of nodes</h2>
 * Until 2026-09-04 {@code smp} attached six hand-written permission nodes to an admin at join
 * ({@code gamemode}, {@code teleport}, {@code give}, {@code time}, {@code weather}, {@code plugins})
 * and {@code hunger-games} and {@code limbo} attached nothing at all - so an admin on two of the
 * three servers had exactly default rights. A list cannot be the answer to "an admin must reliably
 * have every permission", because a list only ever knows what somebody wrote down: every plugin
 * added later brings nodes nobody adds to it, and the way that surfaces is an admin being refused
 * by a command in the middle of whatever they were called in to fix.
 *
 * <p>Operator is the only thing on Paper that covers what has not been enumerated, including nodes
 * belonging to third-party plugins. So {@code admin-permissions} is retired and this replaces it.
 * Decided by the owner 2026-09-04.</p>
 *
 * <h2>The cost, and the sweep that pays it</h2>
 * {@code setOp} is <b>persistent</b>: Bukkit writes {@code ops.json} to disk, so an operator
 * outlives the session that was given one. Everything below follows from that single fact.
 *
 * <p>A server that stops between a join and a quit - a crash, a {@code SIGKILL} at the end of
 * {@code stop_grace_period}, a container the host restarted - leaves the admin in {@code ops.json},
 * where nothing would ever take them out again. {@link #sweep()} is the answer and it runs at every
 * plugin enable: <b>every</b> operator is removed, unconditionally, with no database query at all.
 * That is deliberately blunter than "remove whoever the database does not list as an admin", and
 * the owner chose it on 2026-09-04 knowing what it costs:</p>
 *
 * <ul>
 *   <li><b>It needs no database.</b> A sweep that asked the database would have to decide what to do
 *       when the database is unreachable, and both answers are bad - de-op everybody on an outage,
 *       or grant a stale list. Asking nothing has no such case.</li>
 *   <li><b>A hand-set emergency operator does not survive a restart.</b> Typing {@code op <name>}
 *       into the console works until the next start and then does not. That is the intended
 *       outcome, not a casualty: {@code discord_user.admin} is the only admin list in this
 *       repository (docs/smp.md#admins), and a second one that persisted quietly on disk is exactly
 *       the thing this design exists to prevent. It is written into the owner's checklist so it
 *       surprises nobody during an incident.</li>
 * </ul>
 *
 * <h2>Why transitions are tracked rather than re-applied</h2>
 * Every {@code setOp} is a disk write. {@link #refresh} is built to be called by the admin-flag
 * watcher on every notification and on every poll tick - which is often, by design - so re-asserting
 * the desired state each time would rewrite {@code ops.json} on a timer for as long as the server
 * runs. This class therefore remembers whom it has opped and calls out only on a change. That makes
 * "op an admin twice" free, which is what lets the caller be careless and the disk not be.
 *
 * <h2>What this class deliberately does not do</h2>
 * It never decides <em>who</em> is an admin. The flag is {@code discord_user.admin}, read by
 * whatever the module already uses for it ({@code smp}'s {@code Identities}, the shared cache
 * elsewhere), and handed in. One truth, no second admin list - the same rule
 * {@link FullServerAdmission} states at length for the same reason.
 *
 * <p>It also holds no Bukkit type, because {@code :common} is compiled against no platform. The two
 * calls that need one arrive through {@link Ops}, which each plugin satisfies with a lambda.</p>
 *
 * <h2>Live revocation</h2>
 * {@link #refresh} exists for the {@code nordtal_admin} channel: an admin whose Discord role is
 * taken away loses operator within seconds rather than at their next disconnect. Until the shared
 * watcher is built, nothing calls it and a revocation takes effect on disconnect - which is why the
 * owner's checklist carries that as a separate, later item rather than as part of this change.
 */
public final class AdminOperators {

    /**
     * The two operations this needs from a platform, so that everything above can be decided without
     * one.
     *
     * <p>Implemented inline by each Paper plugin - {@code Bukkit.getOfflinePlayer(uuid).setOp(...)}
     * and {@code Bukkit.getOperators()} - rather than as a class per module, because three identical
     * adapter classes is the duplication this design is trying not to have.</p>
     */
    public interface Ops {

        /** Grant or remove operator. Persistent: Bukkit writes {@code ops.json}. */
        void setOp(UUID player, boolean operator);

        /** Everybody currently carrying operator, whether online or not. */
        Set<UUID> operators();
    }

    private final Ops ops;

    /**
     * Whom <em>this</em> object has opped, so a repeated call is free.
     *
     * <p>It is not a second source of truth about who is an admin: it is emptied by {@link #sweep()}
     * at enable and only ever grows through a call that also wrote to disk. If it and
     * {@code ops.json} ever disagreed, the sweep on the next start settles it by removing both.</p>
     */
    private final Set<UUID> opped = ConcurrentHashMap.newKeySet();

    public AdminOperators(final Ops ops) {
        this.ops = ops;
    }

    /**
     * Remove operator from everybody, unconditionally. Call once, at plugin enable, before any join
     * can be processed.
     *
     * <p>This is what makes an operator a property of the running session rather than of the disk.
     * See the class javadoc for why it asks nothing and spares nobody.</p>
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
     * A player left. Removes operator if this object granted it.
     *
     * <p>Unconditional on the admin flag on purpose: the flag may have changed while they were
     * online, and the question here is only whether an operator this object created is still
     * standing.</p>
     */
    public void onQuit(final UUID player) {
        set(player, false);
    }

    /**
     * Re-derive operator for everybody online from the authoritative admin set.
     *
     * <p>For the {@code nordtal_admin} watcher: on a notification or a poll tick, the caller reads
     * the admin set in full and hands it here with whoever is online. Anybody online and admin gains
     * operator, anybody online and not loses it. Players who are not online are not touched - they
     * hold nothing, because a quit removed it.</p>
     *
     * <p>Cheap to call repeatedly: only a change reaches {@link Ops#setOp}, so a tick on which
     * nothing changed writes nothing to disk. That is the property that makes the poll affordable.</p>
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
