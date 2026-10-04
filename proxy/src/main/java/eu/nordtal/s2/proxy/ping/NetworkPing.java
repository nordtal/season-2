package eu.nordtal.s2.proxy.ping;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.proxy.ProxyMessages;
import eu.nordtal.s2.proxy.launch.LaunchCountdown;
import eu.nordtal.s2.proxy.phase.PhaseWatch;
import eu.nordtal.s2.settings.network.PlayersSpec;
import java.time.Clock;
import java.util.Locale;
import net.kyori.adventure.text.Component;

/**
 * What the server browser shows: the MOTD for the current phase and the player limit the gate enforces.
 *
 * Nothing on this path blocks: Velocity awaits this event, so every value is a field read.
 */
public final class NetworkPing {

    private final ProxyServer proxy;
    private final PlayersSpec players;
    private final PhaseWatch phases;
    private final SnapshotStore snapshots;
    private final MessageRenderer renderer;
    private final Locale language;
    private final Clock clock;
    private final java.util.Optional<com.velocitypowered.api.util.Favicon> favicon;

    /**
     * Takes the icon for every ping.
     *
     * @param players  the network's limit, read on every ping so a change in Steward shows on the next one
     * @param language the network's default language, since a ping carries no reader
     * @param favicon  the 64 x 64 icon, or empty for a ping without one
     */
    public NetworkPing(
            final ProxyServer proxy,
            final PlayersSpec players,
            final PhaseWatch phases,
            final SnapshotStore snapshots,
            final MessageRenderer renderer,
            final Locale language,
            final Clock clock,
            final java.util.Optional<com.velocitypowered.api.util.Favicon> favicon) {
        this.favicon = java.util.Objects.requireNonNull(favicon, "favicon");
        this.proxy = proxy;
        this.players = players;
        this.phases = phases;
        this.snapshots = snapshots;
        this.renderer = renderer;
        this.language = language;
        this.clock = clock;
    }

    @Subscribe
    public void onPing(final ProxyPingEvent event) {
        final ServerPing.Builder ping =
                event.getPing().asBuilder().description(description()).maximumPlayers(players.maxPlayers());
        favicon.ifPresent(ping::favicon);
        event.setPing(ping.build());
    }

    /** The text for the current phase, in the network's language: a ping carries no reader. */
    private Component description() {
        // One read, not two: a ping mid-refresh could otherwise pair a stale phase with a new instant.
        final PhaseWatch.Known known = phases.known();
        final ServerListContext list = ServerListContext.of(
                snapshots.current(),
                proxy.getPlayerCount(),
                players.maxPlayers(),
                LaunchCountdown.render(renderer.raw(), language, known.launch(), clock.instant()));
        final ProxyMessages.Motd motd = ProxyMessages.MESSAGES.motd();
        return renderer.format(
                language,
                switch (known.phase()) {
                    case PRE_LAUNCH -> motd.preLaunch(list);
                    case PRE_EVENT -> motd.preEvent(list);
                    case START_EVENT -> motd.startEvent(list);
                    case SMP -> motd.smp(list);
                    case MAINTENANCE -> motd.maintenance();
                });
    }
}
