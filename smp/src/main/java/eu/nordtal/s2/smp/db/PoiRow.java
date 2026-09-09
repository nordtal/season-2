package eu.nordtal.s2.smp.db;

import java.util.UUID;

/**
 * One point of interest as it is stored.
 *
 * <p>POIs are public and unlimited: anyone may create one, everyone sees every one, and admins may
 * delete any. {@code createdBy} is a credit, not a permission.
 */
public record PoiRow(UUID id, String name, String world, int x, int y, int z, String createdBy) {
}
