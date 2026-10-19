package eu.nordtal.season.smp.duel;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntSupplier;

/** Slots stack upwards from the configured base Y. Each call reads the settings, and a taken slot keeps its arena. */
public final class ArenaSlots {

    private final IntSupplier limit;
    private final IntSupplier baseY;
    private final IntSupplier spacing;
    private final Set<Integer> taken = new HashSet<>();

    public ArenaSlots(final IntSupplier limit, final IntSupplier baseY, final IntSupplier spacing) {
        this.limit = limit;
        this.baseY = baseY;
        this.spacing = spacing;
    }

    /** Returns empty when every slot is taken. */
    public Optional<Integer> claim() {
        for (int slot = 0; slot < limit.getAsInt(); slot++) {
            if (taken.add(slot)) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    public void release(final int slot) {
        taken.remove(slot);
    }

    public int yOf(final int slot) {
        return baseY.getAsInt() + slot * spacing.getAsInt();
    }

    public int inUse() {
        return taken.size();
    }

    public boolean isFull() {
        return taken.size() >= limit.getAsInt();
    }
}
