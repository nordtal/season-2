package eu.nordtal.season.proxy.ping;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.proxy.ProxyMessages;
import eu.nordtal.season.proxy.launch.LaunchCountdown;
import eu.nordtal.season.proxy.phase.PhaseWatch;
import eu.nordtal.season.settings.network.PlayersSpec;
import java.time.Clock;
import java.util.Locale;
import net.kyori.adventure.text.Component;

/** Sets the MOTD of the current phase and the player limit in the ping. It reads fields only and never blocks. */
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
     * @param players  the player limit of the network
     * @param language the default language of the network
     * @param favicon  the 64 x 64 icon, or empty for no icon
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

    /** Returns the text of the current phase in the default language. */
    private Component description() {
        final PhaseWatch.Known known = phases.known();
        final ServerListContext list = ServerListContext.of(
                snapshots.current(),
                proxy.getPlayerCount(),
                players.maxPlayers(),
                renderer.raw().format(language, LaunchCountdown.left(known.launch(), clock.instant())));
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
