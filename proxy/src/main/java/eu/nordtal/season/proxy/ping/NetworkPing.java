package eu.nordtal.season.proxy.ping;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import eu.nordtal.season.common.season.SeasonMode;
import eu.nordtal.season.common.season.SeasonState;
import eu.nordtal.season.messagerendering.MessageRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.value.Example;
import eu.nordtal.season.proxy.launch.LaunchCountdown;
import eu.nordtal.season.proxy.season.SeasonWatch;
import eu.nordtal.season.settings.network.PlayersSpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import net.kyori.adventure.text.Component;

/** Sets the MOTD of the season state and the player limit in the ping. It reads fields only and never blocks. */
public final class NetworkPing {

    /** The line of the server list under the brand mark, in the gate's order. */
    enum State {

        /** The season has no launch date. */
        CLOSED,

        /** The launch date lies ahead. */
        COUNTDOWN,

        /** The season is launched and in maintenance. */
        MAINTENANCE,

        /** The season is launched and the active mode is {@link SeasonMode#HUNGER_GAMES}. */
        HUNGER_GAMES,

        /** The season is launched and the active mode is {@link SeasonMode#SMP}. */
        SMP;

        /** Returns the line of the state at the instant. */
        static State of(final SeasonState season, final Instant now) {
            return switch (season.opening(now)) {
                case CLOSED -> CLOSED;
                case COUNTDOWN -> COUNTDOWN;
                case LAUNCHED -> {
                    if (season.maintenance()) {
                        yield MAINTENANCE;
                    }
                    yield season.mode() == SeasonMode.HUNGER_GAMES ? HUNGER_GAMES : SMP;
                }
            };
        }
    }

    /** The server list's values. The state picks the line; the countdown is that of {@link LaunchCountdown}. */
    record ServerList(
            State state,
            ServerListContext list,
            LaunchCountdown.Stage countdown,
            @Example("2") long days,
            @Example("5") long hours,
            @Example("40") long minutes) {}

    static final DocumentSpec<ServerList> SERVER_LIST = DocumentSpec.game(
                    "proxy", "server-list", "Server list", DocumentSpec.Shape.SERVER_LIST, ServerList.class)
            .titled("Server list description");

    private final ProxyServer proxy;
    private final PlayersSpec players;
    private final SeasonWatch seasons;
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
            final SeasonWatch seasons,
            final SnapshotStore snapshots,
            final MessageRenderer renderer,
            final Locale language,
            final Clock clock,
            final java.util.Optional<com.velocitypowered.api.util.Favicon> favicon) {
        this.favicon = java.util.Objects.requireNonNull(favicon, "favicon");
        this.proxy = proxy;
        this.players = players;
        this.seasons = seasons;
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

    /** Returns the text of the season state in the default language. */
    private Component description() {
        final SeasonState season = seasons.state();
        final Instant now = clock.instant();
        final ServerListContext list =
                ServerListContext.of(snapshots.current(), proxy.getPlayerCount(), players.maxPlayers());
        final LaunchCountdown.Countdown left = LaunchCountdown.left(season.launch(), now);
        return renderer.format(
                language,
                SERVER_LIST.with(new ServerList(
                        State.of(season, now), list, left.countdown(), left.days(), left.hours(), left.minutes())));
    }
}
