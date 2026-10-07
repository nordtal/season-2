package eu.nordtal.season.smp.wheel;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.smp.port.PrizeSource;

/** The wheel as a prize source: the row a granted spin lands in. */
public final class ExtraSpins implements PrizeSource {

    private final SpinDao dao;

    public ExtraSpins(final SpinDao dao) {
        this.dao = dao;
    }

    @Override
    public void grant(final DiscordId discordId, final int spins) {
        dao.grantSpins(discordId, spins);
    }
}
