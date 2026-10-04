package eu.nordtal.s2.smp.grave;

import java.util.UUID;

/**
 * A grave that ran out of time, as the delete statement hands it back.
 *
 * Its contents decay with it; what is left says which display to take down and where to play the sound.
 */
public record ExpiredGrave(UUID id, String world, int x, int y, int z) {}
