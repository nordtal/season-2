package eu.nordtal.season.smp.eye;

import java.util.List;

/** Where an eye of ender thrown in a world flies, given the map's End portals known in that world. */
sealed interface EyeTarget {

    /** The eye flies to this portal of the map. */
    record ToPortal(Portal portal) implements EyeTarget {}

    /**
     * No portal of the map is known in the world, or its scan has not ended yet.
     * The eye is not thrown, as in vanilla where no stronghold is found.
     */
    record NotThrown() implements EyeTarget {}

    /** Picks the target for an eye thrown at x and z. */
    static EyeTarget of(final List<Portal> portals, final double x, final double z) {
        return Portal.nearest(portals, x, z).<EyeTarget>map(ToPortal::new).orElseGet(NotThrown::new);
    }
}
