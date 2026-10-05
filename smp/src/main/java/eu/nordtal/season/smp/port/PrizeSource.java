package eu.nordtal.season.smp.port;

import eu.nordtal.season.common.id.DiscordId;

/**
 * The wheel's side of a contribution: how many extra spins a share earns, and handing them out.
 *
 * Progress pays through it and the NPC menu forecasts with it, so the thresholds live in the wheel alone.
 */
public interface PrizeSource {

    /** How many extra spins {@code sharePercent} of one objective's target earns, zero below the first threshold. */
    int extraSpinsFor(double sharePercent);

    /** Grants {@code spins} extra spins; blocking, and part of the caller's transaction when one is open. */
    void grant(DiscordId discordId, int spins);
}
