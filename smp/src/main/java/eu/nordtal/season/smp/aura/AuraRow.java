package eu.nordtal.season.smp.aura;

import java.util.UUID;

/**
 * One line of the aura leaderboard.
 *
 * Carries the Minecraft UUID, since this repository stores no names; the board looks the name up at render time.
 */
public record AuraRow(UUID mcUuid, int aura) {}
