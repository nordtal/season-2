package eu.nordtal.season.smp.grave;

import java.util.UUID;

/** The position of a grave that {@link GraveDao#expireGravesOlderThan} deleted. */
public record ExpiredGrave(UUID id, String world, int x, int y, int z) {}
