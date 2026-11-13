package eu.nordtal.season.underground.transplant;

/** Smooth noise between -1 and 1 that depends only on the position in the world, so neighbouring tiles agree. */
final class Noise {

    private static final double PERIOD = 9.0;

    private Noise() {}

    static double at(final int x, final int y, final int z) {
        return at(x, y, z, PERIOD);
    }

    /** The noise with waves of another length, in blocks. */
    static double at(final int x, final int y, final int z, final double period) {
        final double fx = x / period;
        final double fy = y / period;
        final double fz = z / period;
        final int x0 = (int) Math.floor(fx);
        final int y0 = (int) Math.floor(fy);
        final int z0 = (int) Math.floor(fz);
        final double tx = smooth(fx - x0);
        final double ty = smooth(fy - y0);
        final double tz = smooth(fz - z0);
        double sum = 0;
        for (int corner = 0; corner < 8; corner++) {
            final int dx = corner & 1;
            final int dy = corner >> 1 & 1;
            final int dz = corner >> 2;
            final double weight = (dx == 1 ? tx : 1 - tx) * (dy == 1 ? ty : 1 - ty) * (dz == 1 ? tz : 1 - tz);
            sum += weight * lattice(x0 + dx, y0 + dy, z0 + dz, Double.doubleToLongBits(period));
        }
        return sum;
    }

    private static double smooth(final double t) {
        return t * t * (3 - 2 * t);
    }

    private static double lattice(final int x, final int y, final int z, final long seed) {
        long hash = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L ^ seed;
        hash = (hash ^ hash >>> 29) * 0xBF58476D1CE4E5B9L;
        hash ^= hash >>> 32;
        return (hash & 0xFFFF) / 32767.5 - 1.0;
    }
}
