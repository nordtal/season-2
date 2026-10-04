package eu.nordtal.s2.smp.navigate;

import java.util.UUID;

/**
 * One point of interest as it is stored.
 *
 * {@code createdBy} is a credit, not a permission: anyone may create one and admins may delete any.
 */
public record PoiRow(UUID id, String name, String world, int x, int y, int z, String createdBy) {}
