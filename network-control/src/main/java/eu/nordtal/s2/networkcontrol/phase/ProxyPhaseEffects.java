package eu.nordtal.s2.networkcontrol.phase;

import com.velocitypowered.api.proxy.ProxyServer;

import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.phase.PhaseEffects;
import eu.nordtal.s2.common.phase.DateChange;
import eu.nordtal.s2.common.phase.PhaseChange;
import eu.nordtal.s2.common.phase.PhaseDirectory;
import eu.nordtal.s2.common.phase.SeasonDates;

import org.slf4j.Logger;

import java.util.Optional;

/**
 * {@code /phase}, as the proxy carries it out.
 *
 * <p>Four small answers to the four questions {@link PhaseEffects} asks, and every one of them is
 * something the bot answers differently:</p>
 * <ul>
 *   <li>The proxy <b>holds</b> the phase, in {@link PhaseWatch}, so {@code /phase} can answer while
 *       the database is unreachable - which is the state somebody runs it in.</li>
 *   <li>It therefore has to <b>refresh</b> after its own write, rather than wait for its own
 *       {@code NOTIFY} to come back around, or the reply and the log line disagree for a moment.</li>
 *   <li>It files admin actions as a {@code WARN} line. There is no admin channel here: if Discord
 *       were reachable, this command would not be the one being used.</li>
 *   <li>Blocking work goes to the proxy scheduler. Brigadier hands us a thread that must not wait,
 *       and this command is most likely to be run at the exact moment the database is slow.</li>
 * </ul>
 */
public final class ProxyPhaseEffects implements PhaseEffects {

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final PhaseDirectory phases;
    private final PhaseWatch watch;

    public ProxyPhaseEffects(final Object plugin, final ProxyServer proxy, final Logger logger,
                             final PhaseDirectory phases, final PhaseWatch watch) {
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
        logger.warn("Season phase switched from the proxy by {} ({}): {} -> {}",
                who.name(), who.discordId().orElse("unlinked"), change.previous(), change.current());
    }

    @Override
    public void recordDate(final NordtalUser who, final boolean launch, final DateChange change) {
        logger.warn("Season date written from the proxy by {} ({}): {} {} -> {}, {} grants moved",
                who.name(), who.discordId().orElse("unlinked"),
                launch ? "launch" : "smp_start",
                SeasonDates.format(change.previous()), SeasonDates.format(change.current()),
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
