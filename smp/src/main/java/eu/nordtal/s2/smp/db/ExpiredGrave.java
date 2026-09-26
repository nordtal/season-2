package eu.nordtal.s2.smp.db;

import java.util.UUID;

/**
 * A grave that ran out of time, as the delete statement hands it back.
 *
 * Not a {@link GraveRow}: the contents are gone by the time this exists, and that is the point rather than an
 * omission - what is in a grave decays with it, the same as vanilla items that despawn. What is left is the three
 * things the server still has to do something about: which display to take down, and where to make the sound.
 */
public record ExpiredGrave(UUID id, String world, int x, int y, int z) {}
