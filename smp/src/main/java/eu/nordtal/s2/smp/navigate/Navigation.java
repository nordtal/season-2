package eu.nordtal.s2.smp.navigate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is currently navigating where.
 *
 * <p>{@code /navigate} is <b>off by default and switched on by the player</b> (docs/smp.md#navigate),
 * so the absence of an entry here is the normal state and not a missing value. HUD line 2 exists
 * only while there is one.
 *
 * <p>Deliberately not persisted. A navigation is a thing you are doing right now, and one that
 * survived a relog would point somebody at a place they had already given up on.
 */
public final class Navigation {

    private final Map<UUID, NavigationTarget> active = new ConcurrentHashMap<>();

    public void set(final UUID player, final NavigationTarget target) {
        active.put(player, target);
    }

    public void clear(final UUID player) {
        active.remove(player);
    }

    public Optional<NavigationTarget> of(final UUID player) {
        return Optional.ofNullable(active.get(player));
    }

    public boolean isNavigating(final UUID player) {
        return active.containsKey(player);
    }

    /**
     * Drops every navigation pointing into a world.
     *
     * <p>Called for the farm world at each daily reset, alongside its POIs: the arrow would still be
     * confident about terrain that no longer exists, and a confident arrow is worse than none.
     */
    public void clearWorld(final String world) {
        active.entrySet().removeIf(entry -> entry.getValue().isIn(world));
    }

    public int size() {
        return active.size();
    }
}
