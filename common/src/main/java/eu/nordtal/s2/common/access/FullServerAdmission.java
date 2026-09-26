package eu.nordtal.s2.common.access;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is let onto a backend that is already full.
 *
 * Admins are exempt from the proxy's player limit, so a full network holds {@code max-players}
 * plus whichever admins joined it and the backend they are routed to has to have room for them.
 * Bukkit's own bypass is not usable, because it reads {@code ops.json} and an admin here is
 * {@code discord_user.admin} in the database - the only admin list in this repository.
 *
 * The flag is <b>read on {@code AsyncPlayerPreLoginEvent}</b> and only read back during the
 * fullness check: that check fires inside the login pipeline, runs twice for one login, and nothing
 * on a login-critical path may query a database. Reading here is therefore non-destructive, so both
 * firings answer the same. {@link #worthAsking(int, int)} keeps the pre-login lookup off servers
 * that are nowhere near full.
 *
 * Players can join between the pre-login and the check, so a login that was not near the cap when
 * it was considered can still be refused. {@link #HEADROOM} covers the ordinary case; a larger burst
 * costs an admin one reconnect.
 *
 * Only admins are held - {@link #remember} with {@code false} removes - so calling it on
 * <b>every</b> pre-login is what keeps an entry from outliving a revoked flag.
 */
public final class FullServerAdmission {

    /** How close to the cap a login must be to be worth a query; the count moves before Paper's check. */
    public static final int HEADROOM = 5;

    private final Set<UUID> admins = ConcurrentHashMap.newKeySet();

    /**
     * Whether a login arriving now is close enough to the cap that the admin flag has to be read.
     *
     * @param online players on this server right now, the player logging in not counted
     * @param max    {@code Bukkit.getMaxPlayers()}
     */
    public static boolean worthAsking(final int online, final int max) {
        return online + HEADROOM >= max;
    }

    /** Records what the pre-login thread found; call it with {@code false} too, so no stale entry remains. */
    public void remember(final UUID mcUuid, final boolean admin) {
        if (admin) {
            admins.add(mcUuid);
        } else {
            admins.remove(mcUuid);
        }
    }

    /** Returns whether this login may pass a full server, without consuming the entry, as validation runs twice. */
    public boolean admits(final UUID mcUuid) {
        return admins.contains(mcUuid);
    }

    public void forget(final UUID mcUuid) {
        admins.remove(mcUuid);
    }

    /** How many warmed answers are held; for tests and for a leak nobody expects. */
    public int size() {
        return admins.size();
    }
}
