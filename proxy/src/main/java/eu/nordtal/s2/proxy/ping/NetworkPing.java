package eu.nordtal.s2.proxy.ping;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.proxy.launch.LaunchCountdown;
import eu.nordtal.s2.proxy.phase.PhaseWatch;
import eu.nordtal.s2.settings.network.MotdSpec;
import eu.nordtal.s2.settings.network.PlayersSpec;
import java.time.Clock;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

/**
 * What the server browser shows: the MOTD for the current phase and the player limit the gate enforces.
 *
 * Nothing on this path blocks: Velocity awaits this event, so every value is a field read.
 */
public final class NetworkPing {

    private final ProxyServer proxy;
    private final Logger logger;
    private final PlayersSpec players;
    private final MotdSpec motd;
    private final String season;
    private final PhaseWatch phases;
    private final SnapshotStore snapshots;
    private final Messages messages;
    private final Clock clock;
    private final java.util.Optional<com.velocitypowered.api.util.Favicon> favicon;

    public NetworkPing(
            final ProxyServer proxy,
            final Logger logger,
            final PlayersSpec players,
            final MotdSpec motd,
            final String season,
            final PhaseWatch phases,
            final SnapshotStore snapshots,
            final Messages messages,
            final Clock clock) {
        this(proxy, logger, players, motd, season, phases, snapshots, messages, clock, java.util.Optional.empty());
    }

    /**
     * Takes the icon for every ping.
     *
     * @param players the network's limit, read on every ping so a change in Steward shows on the next one
     * @param season  the season's name, for {@code {season}}
     * @param favicon the 64 x 64 icon, or empty for a ping without one
     */
    public NetworkPing(
            final ProxyServer proxy,
            final Logger logger,
            final PlayersSpec players,
            final MotdSpec motd,
            final String season,
            final PhaseWatch phases,
            final SnapshotStore snapshots,
            final Messages messages,
            final Clock clock,
            final java.util.Optional<com.velocitypowered.api.util.Favicon> favicon) {
        this.favicon = java.util.Objects.requireNonNull(favicon, "favicon");
        this.proxy = proxy;
        this.logger = logger;
        this.players = players;
        this.motd = motd;
        this.season = season;
        this.phases = phases;
        this.snapshots = snapshots;
        this.messages = messages;
        this.clock = clock;
    }

    @Subscribe
    public void onPing(final ProxyPingEvent event) {
        final ServerPing.Builder ping =
                event.getPing().asBuilder().description(description()).maximumPlayers(players.maxPlayers());
        favicon.ifPresent(ping::favicon);
        event.setPing(ping.build());
    }

    private Component description() {
        // One read, not two: a ping mid-refresh could otherwise pair a stale phase with a new instant.
        final PhaseWatch.Known known = phases.known();
        final SeasonPhase phase = known.phase();
        final String template = motdFor(phase);
        // English: a ping carries no player whose language could be looked up.
        final String countdown = LaunchCountdown.render(messages, Locale.ENGLISH, known.launch(), clock.instant());
        final String substituted = Placeholders.apply(
                template, proxy, phase, season, players.maxPlayers(), snapshots.current(), countdown);

        try {
            return MiniMessage.miniMessage().deserialize(substituted);
        } catch (final RuntimeException malformed) {
            // A mistyped tag must not take the ping down; the unparsed text still reads.
            logger.warn("the MOTD for {} is not valid MiniMessage; showing it unparsed", phase, malformed);
            return Component.text(substituted);
        }
    }

    private String motdFor(final SeasonPhase phase) {
        return switch (phase) {
            case PRE_LAUNCH -> motd.preLaunch();
            case PRE_EVENT -> motd.preEvent();
            case START_EVENT -> motd.startEvent();
            case SMP -> motd.smp();
            case MAINTENANCE -> motd.maintenance();
        };
    }
}
