package eu.nordtal.season.smp.navigate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Maps each navigating player to a target. Nothing is persisted. */
public final class Navigation {

    /** The horizontal distance in blocks at which a navigation ends by itself. */
    public static final double ARRIVAL_RADIUS = 2;

    private final Map<UUID, NavigationTarget> active = new ConcurrentHashMap<>();

    public void set(final UUID player, final NavigationTarget target) {
        active.put(player, target);
    }

    public void clear(final UUID player) {
        active.remove(player);
    }

    /** Ends the navigation of {@code player} and returns its target if they stand within {@link #ARRIVAL_RADIUS}. */
    public Optional<NavigationTarget> arrive(final UUID player, final String world, final double x, final double z) {
        final NavigationTarget target = active.get(player);
        if (target == null || !target.isIn(world) || target.distanceFrom(x, z) > ARRIVAL_RADIUS) {
            return Optional.empty();
        }
        active.remove(player);
        return Optional.of(target);
    }

    public Optional<NavigationTarget> of(final UUID player) {
        return Optional.ofNullable(active.get(player));
    }

    public boolean isNavigating(final UUID player) {
        return active.containsKey(player);
    }

    public void clearWorld(final String world) {
        active.entrySet().removeIf(entry -> entry.getValue().isIn(world));
    }

    public int size() {
        return active.size();
    }
}
