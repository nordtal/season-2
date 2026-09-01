package eu.nordtal.s2.smp.db;

import java.util.UUID;

/**
 * One line of the aura leaderboard.
 *
 * <p>Carries the Minecraft UUID rather than a name because this repository stores no Minecraft
 * names: they are the server's to resolve and the player's to change. The board looks the name up
 * at render time.
 */
public record AuraRow(UUID mcUuid, int aura) {
}
