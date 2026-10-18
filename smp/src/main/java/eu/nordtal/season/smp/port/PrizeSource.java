package eu.nordtal.season.smp.port;

import eu.nordtal.season.common.id.DiscordId;

/** Grants the wheel spins of an objective's spin budget. */
public interface PrizeSource {

    /** Grants {@code spins} extra spins. It blocks and joins the open transaction of the caller. */
    void grant(DiscordId discordId, int spins);
}
