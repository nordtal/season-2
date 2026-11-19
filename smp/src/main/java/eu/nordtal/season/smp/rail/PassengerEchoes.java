package eu.nordtal.season.smp.rail;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Remembers the passengers one client last heard for each vehicle, to find a repeat of its own mount.
 * The server sends a mount twice, and the client starts its riding sound loops on each, so they would play twice.
 */
final class PassengerEchoes {

    private final Map<Integer, int[]> heard = new HashMap<>();

    /**
     * Returns whether {@code passengers} of {@code vehicle} repeat a mount of {@code self} the client already heard.
     * It records them as heard either way; {@code self} is the entity id of the player the packet goes to.
     */
    boolean repeats(final int vehicle, final int[] passengers, final int self) {
        final int[] before = heard.put(vehicle, passengers.clone());
        return self >= 0
                && before != null
                && Arrays.equals(before, passengers)
                && Arrays.stream(passengers).anyMatch(passenger -> passenger == self);
    }

    /** Forgets {@code entity}, which the client removed or is about to see anew. */
    void forget(final int entity) {
        heard.remove(entity);
    }

    /** Forgets every vehicle, as the client left its level. */
    void forgetAll() {
        heard.clear();
    }
}
