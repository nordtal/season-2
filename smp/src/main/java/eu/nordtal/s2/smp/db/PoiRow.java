package eu.nordtal.s2.smp.db;

import java.util.UUID;

/**
 * One point of interest as it is stored.
 *
 * <p>POIs are public and unlimited: anyone may create one, everyone sees every one of them, and
 * admins may delete any (docs/smp.md#navigate). {@code createdBy} is therefore a credit rather than
 * a permission - the only thing it is used for is showing who put it there.
 */
public record PoiRow(UUID id, String name, String world, int x, int y, int z, String createdBy) {
}
