package eu.nordtal.season.smp.grave;

/** Decides whether a grave places or removes its barrier. A barrier replaces only an empty block. */
final class GraveBarrier {

    enum There {
        EMPTY,
        BARRIER,
        OTHER
    }

    private GraveBarrier() {}

    static boolean placesInto(final There there) {
        return there == There.EMPTY;
    }

    /** Returns whether a removed grave clears its block. This is true for a barrier that no other grave uses. */
    static boolean clears(final There there, final boolean anotherGraveThere) {
        return there == There.BARRIER && !anotherGraveThere;
    }
}
