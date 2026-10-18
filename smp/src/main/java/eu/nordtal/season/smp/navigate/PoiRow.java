package eu.nordtal.season.smp.navigate;

import java.util.UUID;

/** A stored POI. {@code createdBy} names the creator and grants no right to the POI. */
public record PoiRow(UUID id, String name, String world, int x, int y, int z, String createdBy) {}
