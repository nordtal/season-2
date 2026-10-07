package eu.nordtal.season.smp.port;

import eu.nordtal.season.common.id.DiscordId;

/** The wheel's side of a contribution: handing out the extra spins an objective's spin budget pays. */
public interface PrizeSource {

    /** Grants {@code spins} extra spins; blocking, and part of the caller's transaction when one is open. */
    void grant(DiscordId discordId, int spins);
}
