package eu.nordtal.season.smp.wheel;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.smp.port.PrizeSource;
import java.util.List;
import java.util.function.Supplier;

/** The wheel as a prize source: the extra spin thresholds, and the row a granted spin lands in. */
public final class ExtraSpins implements PrizeSource {

    private final SpinDao dao;
    /** {@code config#wheel-extra-spin-percents}, read on every call, so the forecast and the payout agree. */
    private final Supplier<List<Integer>> thresholds;

    public ExtraSpins(final SpinDao dao, final Supplier<List<Integer>> thresholds) {
        this.dao = dao;
        this.thresholds = thresholds;
    }

    @Override
    public int extraSpinsFor(final double sharePercent) {
        return PrizeDraw.extraSpinsFor(thresholds.get(), sharePercent);
    }

    @Override
    public void grant(final DiscordId discordId, final int spins) {
        dao.grantSpins(discordId, spins);
    }
}
