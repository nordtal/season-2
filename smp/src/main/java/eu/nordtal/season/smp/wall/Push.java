package eu.nordtal.season.smp.wall;

import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.jspecify.annotations.Nullable;

/** The push back inside for a player who walked through the wall. */
final class Push {

    /** The speed in blocks per tick that turns a player on foot back, a little above a sprint. */
    static final double WALK = 0.4;

    /** The speed in blocks per tick that turns a glider or a vehicle back, above the fastest glide into the wall. */
    static final double REBOUND = 1.4;

    /** The speed in blocks per tick that lifts a glider outside the wall while it turns back. */
    static final double LIFT = 0.35;

    /** How far before the wall a player on foot is turned back, in blocks, so the camera stays inside. */
    static final double CUSHION = 0.75;

    /** Whether a player on foot who moves by {@code dx}, {@code dz} from {@code x}, {@code z} comes up to the wall. */
    static boolean reaches(final Wall wall, final double x, final double z, final double dx, final double dz) {
        final double ahead = 1 + CUSHION / Math.hypot(dx, dz);
        return Double.isFinite(wall.exit(x, z, x + dx * ahead, z + dz * ahead));
    }

    /** A player is pushed at most this often, in milliseconds. */
    static final long PAUSE = 500;

    private Push() {}

    /**
     * The velocity {x, y, z} that pushes a player at {@code x}, {@code z} back inside, or null while inside.
     * A player on foot keeps the {@code vertical} speed they have and a {@code gliding} one is lifted.
     */
    static double @Nullable [] velocity(
            final Wall wall, final double x, final double z, final boolean gliding, final double vertical) {
        if (!wall.hasOutline() || wall.inside(x, z)) {
            return null;
        }
        final double[] inward = wall.inward(x, z);
        final double speed = gliding ? REBOUND : WALK;
        return new double[] {inward[0] * speed, gliding ? LIFT : vertical, inward[1] * speed};
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
            return new Mount(true, normal[0] * REBOUND, vertical, normal[1] * REBOUND);
        }
        return new Mount(restore, -dx / speed * REBOUND, vertical, -dz / speed * REBOUND);
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
        final double impulse = Math.max(-approach, REBOUND) - approach;
        return new double[] {dx + impulse * normal[0], dz + impulse * normal[1]};
    }

    static boolean bypass(final GameMode mode, final boolean admin) {
        return admin && (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR);
    }

    /** The speed in blocks per tick that walks a player whom a save left outside back in. */
    static final double DRIFT = 0.25;

    /** How long a player whom a save left outside drifts in before a teleport takes it inside, in milliseconds. */
    static final long DRIFT_PATIENCE = 20_000;

    /** The velocity {x, z} that walks a player at {@code x}, {@code z} slowly inside, or null while inside. */
    static double @Nullable [] drift(final Wall wall, final double x, final double z) {
        if (!wall.hasOutline() || wall.inside(x, z)) {
            return null;
        }
        final double[] inward = wall.inward(x, z);
        return new double[] {inward[0] * DRIFT, inward[1] * DRIFT};
    }

    /** Blends a gentle inward nudge into a glider's horizontal velocity without stalling its flight. */
    static double @Nullable [] driftGlide(
            final Wall wall, final double x, final double z, final double vx, final double vz) {
        if (!wall.hasOutline() || wall.inside(x, z)) {
            return null;
        }
        final double[] inward = wall.inward(x, z);
        final double towards = vx * inward[0] + vz * inward[1];
        if (towards >= DRIFT) {
            return new double[] {vx, vz};
        }
        final double nudge = Math.min(DRIFT, DRIFT - towards);
        return new double[] {vx + nudge * inward[0], vz + nudge * inward[1]};
    }

    /** Whether a player whom a save left outside {@code elapsed} milliseconds ago still drifts in. */
    static boolean drifts(final long elapsed) {
        return elapsed <= DRIFT_PATIENCE;
    }

    static boolean fallback(final double distance, final long elapsed) {
        return distance > 2 || elapsed > 1000;
    }

    /** Whether a player last pushed at {@code last} may be pushed again at {@code now}. */
    static boolean due(final long last, final long now) {
        return now - last >= PAUSE;
    }
}
