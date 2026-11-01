package eu.nordtal.season.smp.rail;

import java.util.UUID;

/** A row of {@code smp_rail_edge_rail}: one rail of an edge, at its place on the track. */
public record EdgeRailRow(UUID edge, int ordinal, int x, int y, int z) {}
