package eu.nordtal.season.smp.rail;

/** A junction of {@code smp_rail_junction}, by its world and position. */
public record NodeRow(String world, int x, int y, int z) {

    public RailPos pos() {
        return new RailPos(x, y, z);
    }
}
