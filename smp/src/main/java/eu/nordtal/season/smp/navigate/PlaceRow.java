package eu.nordtal.season.smp.navigate;

/** A world and a block position, which is all a {@code /navigate} target ever needs. */
public record PlaceRow(String world, int x, int y, int z) {}
