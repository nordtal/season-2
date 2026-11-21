package eu.nordtal.season.smp.wall;

import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.jspecify.annotations.Nullable;

/** The push back inside for a player who walked through the wall. */
final class Push {

    static final double HORIZONTAL = 1.4;
    static final double UP = 0.35;

    /** A player is pushed at most this often, in milliseconds. */
    static final long PAUSE = 500;

    private Push() {}

    /** The velocity {x, y, z} that pushes a player at {@code x}, {@code z} back inside, or null while inside. */
    static double @Nullable [] velocity(final Wall wall, final double x, final double z) {
        if (!wall.hasOutline() || wall.inside(x, z)) {
            return null;
        }
        final double[] inward = wall.inward(x, z);
        return new double[] {inward[0] * HORIZONTAL, UP, inward[1] * HORIZONTAL};
    }

    record Mount(boolean restore, double x, double y, double z) {}

    static @Nullable Mount mount(
            final Wall wall,
            final double x,
            final double z,
            final double dx,
            final double dz,
            final double radius,
            final double vertical,
            final boolean onRail) {
        final double speed = Math.hypot(dx, dz);
        final boolean outside = !wall.inside(x, z);
        final boolean crossed = speed > 0 && Double.isFinite(wall.exit(x - dx, z - dz, x, z));
        final double reach = speed > 0 ? 2 + radius / speed : 0;
        if (!outside && !crossed && (speed == 0 || !Double.isFinite(wall.exit(x, z, x + dx * reach, z + dz * reach)))) {
            return null;
        }
        final boolean restore = outside || crossed;
        if (onRail) return new Mount(restore, 0, 0, 0);
        if (outside) {
            final double[] normal = wall.inward(x, z);
            return new Mount(true, normal[0] * HORIZONTAL, vertical, normal[1] * HORIZONTAL);
        }
        return new Mount(restore, -dx / speed * HORIZONTAL, vertical, -dz / speed * HORIZONTAL);
    }

    static Entity root(final Entity entity) {
        Entity root = entity;
        Entity vehicle = entity.getVehicle();
        while (vehicle != null) {
            root = vehicle;
            vehicle = vehicle.getVehicle();
        }
        return root;
    }

    /** Reserve four ticks of forward travel and a block of camera clearance at a shallow approach. */
    static double @Nullable [] glide(
            final Wall wall, final double x, final double z, final double dx, final double dz) {
        final double toX = x + 4 * dx;
        final double toZ = z + 4 * dz;
        final double hit = wall.exit(x, z, toX, toZ);
        final double[] normal =
                Double.isFinite(hit) ? wall.inward(x + hit * 4 * dx, z + hit * 4 * dz) : wall.nearbyNormal(toX, toZ, 1);
        if (normal == null) {
            return null;
        }
        final double approach = dx * normal[0] + dz * normal[1];
        if (approach >= -1e-7) {
            return null;
        }
        final double impulse = Math.max(-approach, HORIZONTAL) - approach;
        return new double[] {dx + impulse * normal[0], dz + impulse * normal[1]};
    }

    static boolean bypass(final GameMode mode, final boolean admin) {
        return admin && (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR);
    }

    static boolean fallback(final double distance, final long elapsed) {
        return distance > 2 || elapsed > 1000;
    }

    /** Whether a player last pushed at {@code last} may be pushed again at {@code now}. */
    static boolean due(final long last, final long now) {
        return now - last >= PAUSE;
    }
}
