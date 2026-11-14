package eu.nordtal.season.smp.wheel;

import eu.nordtal.season.smp.config.WheelPrizeSpec;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Each player's wheel: its prizes stay until a turn on them ends, which leaves them where they stopped.
 * The next open or spin then draws new prizes. Nothing is kept past a server stop. Callable from any thread.
 */
final class WheelFaces {

    /** The face a player's wheel shows next, and whether it was drawn just now, so its prizes pop in. */
    record Next(WheelPlan.Face face, boolean drawn) {}

    /** How a player's wheel stood before a turn, so a spin that goes back can put it back. */
    record Before(WheelPlan.@Nullable Face face, boolean spent) {}

    private final Map<UUID, WheelPlan.Face> faces = new ConcurrentHashMap<>();

    /** The players whose last turn ended, so their next open or spin draws new prizes. */
    private final Set<UUID> turned = ConcurrentHashMap.newKeySet();

    private final Random random;

    WheelFaces(final Random random) {
        this.random = random;
    }

    /** The player's wheel for an open, kept from now on until a turn on it ends. */
    Next next(final UUID id, final List<WheelPrizeSpec> pool) {
        final boolean spent = turned.remove(id);
        final Next[] next = {null};
        faces.compute(id, (key, kept) -> {
            next[0] = choose(kept, spent, pool);
            return next[0].face();
        });
        return next[0];
    }

    /** The player's wheel for a spin, which keeps nothing: only its turn does, with {@link #turning}. */
    Next peek(final UUID id, final List<WheelPrizeSpec> pool) {
        return choose(faces.get(id), turned.contains(id), pool);
    }

    /**
     * Drawn when there is no face, a turn on it ended, or a prize on it left {@code pool}.
     * A new face keeps the grain's parity, so the disc does not jump.
     */
    private Next choose(final WheelPlan.@Nullable Face kept, final boolean spent, final List<WheelPrizeSpec> pool) {
        if (!spent && kept != null && kept.drawnFrom(pool)) {
            return new Next(kept, false);
        }
        final WheelPlan.Face drawn = WheelPlan.Face.random(pool, random);
        return new Next(new WheelPlan.Face(drawn.places(), kept == null ? 0 : kept.parity()), true);
    }

    /** Puts the player's wheel where a turn leaves it, and returns how it stood before. */
    Before turning(final UUID id, final WheelPlan.Face after) {
        final boolean spent = turned.remove(id);
        return new Before(faces.put(id, after), spent);
    }

    /** A spin that goes back leaves the wheel as it was before it. */
    void back(final UUID id, final Before before) {
        if (before.face() == null) {
            faces.remove(id);
        } else {
            faces.put(id, before.face());
        }
        if (before.spent()) {
            turned.add(id);
        }
    }

    /** The player's turn ended: the wheel keeps showing where it stopped until the next open or spin. */
    void ended(final UUID id) {
        turned.add(id);
    }
}
