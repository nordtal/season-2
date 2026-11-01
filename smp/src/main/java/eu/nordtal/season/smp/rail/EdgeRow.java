package eu.nordtal.season.smp.rail;

import java.util.UUID;

/** A row of {@code smp_rail_edge}: the track between two nodes, without its rails. */
public record EdgeRow(UUID id, String world, int fromX, int fromY, int fromZ, int toX, int toY, int toZ, int length) {}
