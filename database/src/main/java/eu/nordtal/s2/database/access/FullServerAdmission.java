package eu.nordtal.s2.database.access;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is let onto a backend that is already full: admins, who are exempt from the proxy's player limit.
 * The flag is read at pre-login and only read back during the fullness check, which runs twice per login.
 */
public final class FullServerAdmission {

    /** How close to the cap a login must be to be worth a query; the count moves before Paper's check. */
    public static final int HEADROOM = 5;

    private final Set<UUID> admins = ConcurrentHashMap.newKeySet();

    /**
     * Returns whether a login arriving now is close enough to the cap that the admin flag has to be read.
     *
     * @param online players on this server right now, the player logging in not counted
     * @param max {@code Bukkit.getMaxPlayers()}
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

    /** Returns how many warmed answers are held. */
    public int size() {
        return admins.size();
    }
}
