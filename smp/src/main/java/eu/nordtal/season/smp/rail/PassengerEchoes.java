package eu.nordtal.season.smp.rail;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Remembers the passengers one client last heard for each vehicle its player rides, to find a repeat of the mount.
 * The server sends a mount twice, and the client starts its riding sound loops on each, so they would play twice.
 */
final class PassengerEchoes {

    private final Map<Integer, int[]> heard = new HashMap<>();

    /**
     * Returns whether {@code passengers} of {@code vehicle} repeat a mount of {@code self} the client already heard.
     * It records them as heard either way; {@code self} is the entity id of the player the packet goes to.
     */
    boolean repeats(final int vehicle, final int[] passengers, final int self) {
        if (self < 0 || !contains(passengers, self)) {
            heard.remove(vehicle);
            return false;
        }
        final int[] before = heard.put(vehicle, passengers.clone());
        return Arrays.equals(before, passengers);
    }

    /** Forgets {@code entity} and every vehicle it rides in, as the client removed it or is about to see it anew. */
    void forget(final int entity) {
        heard.remove(entity);
        heard.values().removeIf(passengers -> contains(passengers, entity));
    }

    /** Forgets every vehicle, as the client left its level. */
    void forgetAll() {
        heard.clear();
    }

    /** Whether no vehicle is remembered. */
    boolean empty() {
        return heard.isEmpty();
    }

    private static boolean contains(final int[] passengers, final int entity) {
        return Arrays.stream(passengers).anyMatch(passenger -> passenger == entity);
    }
}
