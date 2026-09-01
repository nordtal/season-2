package eu.nordtal.s2.smp.db;

/** A world and a block position, which is all a {@code /navigate} target ever needs. */
public record PlaceRow(String world, int x, int y, int z) {
}
