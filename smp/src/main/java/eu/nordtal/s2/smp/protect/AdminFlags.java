package eu.nordtal.s2.smp.protect;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is an admin, cached for as long as they are online.
 *
 * <p>The flag lives in {@code discord_user.admin}, mirrored there by the bot from the Discord role -
 * there is no LuckPerms and no second admin list (docs/smp.md#admins). It is cached because the
 * protection listener asks the question on <em>every block interaction</em>, and a database round
 * trip per click would be the main-thread mistake this repository already made once, in
 * {@code /hg start}, on 2026-09-01.
 *
 * <p>The cost of caching is that promoting somebody takes effect when they next join. That is the
 * right trade for a handful of people who are promoted about once a season.
 */
public final class AdminFlags {

    private final Set<UUID> admins = ConcurrentHashMap.newKeySet();

    public void set(final UUID player, final boolean admin) {
        if (admin) {
            admins.add(player);
        } else {
            admins.remove(player);
        }
    }

    public boolean isAdmin(final UUID player) {
        return admins.contains(player);
    }

    public void forget(final UUID player) {
        admins.remove(player);
    }

    public int size() {
        return admins.size();
    }
}
