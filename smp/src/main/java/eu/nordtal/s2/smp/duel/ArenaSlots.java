package eu.nordtal.s2.smp.duel;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Which height an arena gets, and which are free.
 *
 * <p>Arenas stack above the spawn area - the first at the configured base, each further one a fixed
 * distance higher - up to the concurrent limit, and anyone beyond that waits in a queue. Slots are
 * handed out lowest-first and returned when a duel ends, so a busy evening does not push the fourth
 * duel into the build limit while slots one and two stand empty.
 *
 * <p>Pure, so the arithmetic that decides where blocks get placed is asserted without placing any.
 */
public final class ArenaSlots {

    private final int limit;
    private final int baseY;
    private final int spacing;
    private final Set<Integer> taken = new HashSet<>();

    public ArenaSlots(final int limit, final int baseY, final int spacing) {
        if (limit <= 0) {
            throw new IllegalArgumentException("concurrent-duel-limit must be at least one, was " + limit);
        }
        if (spacing <= 0) {
            throw new IllegalArgumentException("duel-arena-spacing must be positive, was " + spacing);
        }
        this.limit = limit;
        this.baseY = baseY;
        this.spacing = spacing;
    }

    /** The lowest free slot, or empty when every arena is in use and the next duel has to queue. */
    public Optional<Integer> claim() {
        for (int slot = 0; slot < limit; slot++) {
            if (taken.add(slot)) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    public void release(final int slot) {
        taken.remove(slot);
    }

    /** The height of a slot's floor. */
    public int yOf(final int slot) {
        return baseY + slot * spacing;
    }

    public int inUse() {
        return taken.size();
    }

    public boolean isFull() {
        return taken.size() >= limit;
    }

    public int limit() {
        return limit;
    }
}
