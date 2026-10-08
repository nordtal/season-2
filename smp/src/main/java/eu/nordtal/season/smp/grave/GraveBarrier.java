package eu.nordtal.season.smp.grave;

/**
 * Whether a grave sets or clears the barrier that makes it solid, decided from what fills its block.
 *
 * A barrier only ever goes into empty air, so no block, plant or water is lost to a grave; such a grave stays open.
 */
final class GraveBarrier {

    /** What fills a grave's block; a barrier is the grave's own, since survival play cannot place one. */
    enum There {
        EMPTY,
        BARRIER,
        OTHER
    }

    private GraveBarrier() {}

    /** Whether drawing a grave sets a barrier into its block. */
    static boolean placesInto(final There there) {
        return there == There.EMPTY;
    }

    /** Whether a grave that is gone clears its block, which another grave standing in it still needs. */
    static boolean clears(final There there, final boolean anotherGraveThere) {
        return there == There.BARRIER && !anotherGraveThere;
    }
}
