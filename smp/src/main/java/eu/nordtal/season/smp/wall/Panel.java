package eu.nordtal.season.smp.wall;

/**
 * One panel of the wall: a flat strip one block wide, centred on {@code x} and {@code z}, along the edge.
 * {@code yaw} turns the strip along its edge, in degrees.
 */
record Panel(double x, double z, float yaw) {

    double distance(final double fromX, final double fromZ) {
        return Math.hypot(x - fromX, z - fromZ);
    }
}
