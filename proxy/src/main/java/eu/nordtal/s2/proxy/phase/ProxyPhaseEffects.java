package eu.nordtal.s2.proxy.phase;

import com.velocitypowered.api.proxy.ProxyServer;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.phase.PhaseEffects;
import eu.nordtal.s2.common.phase.DateChange;
import eu.nordtal.s2.common.phase.PhaseChange;
import eu.nordtal.s2.common.phase.PhaseDirectory;
import eu.nordtal.s2.common.phase.SeasonDates;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * {@code /phase}, as the proxy carries it out.
 *
 * It refreshes after its own write, logs admin actions as {@code WARN}, and runs blocking work on the scheduler.
 */
public final class ProxyPhaseEffects implements PhaseEffects {

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final PhaseDirectory phases;
    private final PhaseWatch watch;

    public ProxyPhaseEffects(
            final Object plugin,
            final ProxyServer proxy,
            final Logger logger,
            final PhaseDirectory phases,
            final PhaseWatch watch) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.phases = phases;
        this.watch = watch;
    }

    @Override
    public PhaseDirectory phases() {
        return phases;
    }

    @Override
    public Optional<Observation> observation() {
        final PhaseWatch.Known known = watch.known();
        return Optional.of(new Observation(known.phase(), watch.everRead(), known.launch()));
    }

    @Override
    public void afterWrite() {
        watch.refresh();
    }

    @Override
    public void recordSwitch(final NordtalUser who, final PhaseChange change) {
        logger.warn(
                "Season phase switched from the proxy by {} ({}): {} -> {}",
                who.name(),
                who.discordId().orElse("unlinked"),
                change.previous(),
                change.current());
    }

    @Override
    public void recordDate(final NordtalUser who, final boolean launch, final DateChange change) {
        logger.warn(
                "Season date written from the proxy by {} ({}): {} {} -> {}, {} grants moved",
                who.name(),
                who.discordId().orElse("unlinked"),
                launch ? "launch" : "smp_start",
                SeasonDates.format(change.previous()),
                SeasonDates.format(change.current()),
                change.grants());
    }

    @Override
    public void async(final Runnable work) {
        proxy.getScheduler().buildTask(plugin, work).schedule();
    }

    @Override
    public void warn(final String what, final Throwable failure) {
        logger.error("/phase failed while {}", what, failure);
    }
}
