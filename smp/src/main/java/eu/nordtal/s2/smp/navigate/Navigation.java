package eu.nordtal.s2.smp.navigate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Who is currently navigating where; not persisted, so a relog ends it. */
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

    /** Drops every navigation pointing into a world, so no arrow points at a deleted POI. */
    public void clearWorld(final String world) {
        active.entrySet().removeIf(entry -> entry.getValue().isIn(world));
    }

    public int size() {
        return active.size();
    }
}
