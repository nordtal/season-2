package eu.nordtal.season.smp.wheel;

import eu.nordtal.season.smp.config.WheelPrizeSpec;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each player's wheel: its prizes stay until a turn on them ends, which leaves them where they stopped.
 * The next open or spin then draws new prizes. Nothing is kept past a server stop.
 */
final class WheelFaces {

    /** The face a player's wheel shows next, and whether it was drawn just now, so its prizes pop in. */
    record Next(WheelPlan.Face face, boolean drawn) {}

    private final Map<UUID, WheelPlan.Face> faces = new ConcurrentHashMap<>();

    /** The players whose last turn ended, so their next open or spin draws new prizes. */
    private final Set<UUID> turned = ConcurrentHashMap.newKeySet();

    private final Random random;

    WheelFaces(final Random random) {
        this.random = random;
    }

    /** The player's wheel, drawn now if they have none, a turn on it ended, or a prize on it left {@code pool}. */
    Next next(final UUID id, final List<WheelPrizeSpec> pool) {
        final boolean spent = turned.remove(id);
        final boolean[] drawn = {false};
        final WheelPlan.Face face = faces.compute(id, (key, kept) -> {
            if (!spent && kept != null && kept.drawnFrom(pool)) {
                return kept;
            }
            drawn[0] = true;
            return WheelPlan.Face.random(pool, random);
        });
        return new Next(face, drawn[0]);
    }

    /** Puts the player's wheel where a turn leaves it, and returns where it stood before. */
    WheelPlan.Face turning(final UUID id, final WheelPlan.Face after) {
        return faces.put(id, after);
    }

    /** A spin that goes back leaves the wheel as it was before it. */
    void back(final UUID id, final WheelPlan.Face before) {
        faces.put(id, before);
    }

    /** The player's turn ended: the wheel keeps showing where it stopped until the next open or spin. */
    void ended(final UUID id) {
        turned.add(id);
    }
}
