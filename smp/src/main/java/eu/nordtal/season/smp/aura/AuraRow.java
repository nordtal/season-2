package eu.nordtal.season.smp.aura;

import java.util.UUID;

/** One row of the aura leaderboard. It holds the Minecraft UUID and no name. */
public record AuraRow(UUID mcUuid, int aura) {}
