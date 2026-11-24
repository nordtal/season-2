package eu.nordtal.season.smp.tunnel;

/** Integer block coordinates inside a world. */
public record BlockPos(int x, int y, int z) {

    /** Returns this position offset by {@code (dx, dy, dz)}. */
    public BlockPos offset(final int dx, final int dy, final int dz) {
        return new BlockPos(x + dx, y + dy, z + dz);
    }
}
