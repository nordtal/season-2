package eu.nordtal.season.smp.rail;

/**
 * How far one ride has come, from 0 at departure to 1 at arrival, and how far it still has to go.
 * Each step covers its share of what was left, so it only grows and stands still while the cart waits; a replan
 * changes how fast it fills from where it stands. Without one it is the distance ridden over the whole route.
 */
final class RideProgress {

    private double done;
    private double left;

    /** Starts a ride with {@code left} blocks to its station. */
    RideProgress(final double left) {
        this.left = Math.max(0, left);
    }

    /** The way to the station was planned again, and {@code left} blocks remain. */
    void replanned(final double left) {
        this.left = Math.max(0, left);
    }

    /** The cart moved and {@code left} blocks remain on the planned way. */
    void moved(final double left) {
        final double now = Math.max(0, left);
        if (now < this.left) {
            done += (1 - done) * (this.left - now) / this.left;
        }
        this.left = now;
        if (now == 0) {
            done = 1;
        }
    }

    /** The cart stands at the station. */
    void arrived() {
        done = 1;
        left = 0;
    }

    /** The share of the ride behind the cart, from 0 to 1. */
    double done() {
        return done;
    }

    /** The whole metres left to the station, rounded up, so it shows 0 only at the station. */
    long metres() {
        return (long) Math.ceil(left - 1e-9);
    }
}
