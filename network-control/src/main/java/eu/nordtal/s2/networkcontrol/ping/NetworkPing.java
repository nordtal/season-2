package eu.nordtal.s2.networkcontrol.ping;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerPing;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.networkcontrol.config.NetworkSpec;
import eu.nordtal.s2.networkcontrol.launch.LaunchCountdown;
import eu.nordtal.s2.networkcontrol.phase.PhaseWatch;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import org.slf4j.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

/**
 * What the server browser shows: the MOTD for the current phase, and the player limit the network
 * actually enforces.
 *
 * <h2>Why the plugin owns this and {@code velocity.toml} does not</h2>
 * The MOTD used to be seeded into {@code velocity.toml} on a fresh volume and owned by the operator
 * afterwards, which meant changing it on a network that had ever started required editing a file
 * inside a Docker volume - and setting {@code VELOCITY_MOTD} in {@code .env} silently did nothing.
 * The entrypoint no longer writes {@code motd} or {@code show-max-players} at all; both live in
 * {@code network.yml} and are answered here. The proxy refuses to start without this plugin
 * ({@code EXPECTED_PLUGINS}), so there is no configuration in which that leaves the browser showing
 * Velocity's own default.
 *
 * <h2>Nothing on this path blocks</h2>
 * A ping is unauthenticated, arrives in bursts from every client with the server in its list, and
 * Velocity waits on this event before answering ({@code @AwaitingEvent}). So: no database call, no
 * lock, no I/O. The phase comes from {@link PhaseWatch}'s last known value, the counts from the
 * proxy's own view, and everything else from a {@link NetworkSnapshot} a timer refreshed - all of
 * them field reads.
 *
 * <h2>The maximum is the real one</h2>
 * {@code maximumPlayers} here is the same number {@code LoginGate} enforces, from the same config
 * key. Before 2026-09-03 the browser advertised 500 while the first backend a player reached
 * refused the 21st - the two numbers could not disagree if they wanted to now, because there is
 * only one.
 */
public final class NetworkPing {

    private final ProxyServer proxy;
    private final Logger logger;
    private final NetworkSpec config;
    private final PhaseWatch phases;
    private final SnapshotStore snapshots;
    private final Messages messages;
    private final Clock clock;

    public NetworkPing(final ProxyServer proxy, final Logger logger, final NetworkSpec config,
                       final PhaseWatch phases, final SnapshotStore snapshots, final Messages messages,
                       final Clock clock) {
        this.proxy = proxy;
        this.logger = logger;
        this.config = config;
        this.phases = phases;
        this.snapshots = snapshots;
        this.messages = messages;
        this.clock = clock;
    }

    @Subscribe
    public void onPing(final ProxyPingEvent event) {
        event.setPing(event.getPing().asBuilder()
                .description(description())
                .maximumPlayers(config.maxPlayers())
                .build());
    }

    private Component description() {
        final SeasonPhase phase = phases.lastKnown();
        final String template = motdFor(phase);
        final Instant launch = phases.launch().orElse(null);
        // English: a ping carries no player, so there is nobody whose language could be looked up.
        final String countdown = LaunchCountdown.render(messages, Locale.ENGLISH, launch, clock.instant());
        final String substituted = Placeholders.apply(template, proxy, phase, config.maxPlayers(),
                snapshots.current(), countdown);

        try {
            return MiniMessage.miniMessage().deserialize(substituted);
        } catch (final RuntimeException malformed) {
            // A MOTD is operator-written text, and a mistyped tag must not take the ping down -
            // every client in the world would then show this network as unreachable rather than as
            // badly configured. The unparsed string is still a readable line.
            logger.warn("network.yml's MOTD for {} is not valid MiniMessage; showing it unparsed",
                    phase, malformed);
            return Component.text(substituted);
        }
    }

    private String motdFor(final SeasonPhase phase) {
        final NetworkSpec.MotdSpec motd = config.motd();
        return switch (phase) {
            case PRE_LAUNCH -> motd.preLaunch();
            case PRE_EVENT -> motd.preEvent();
            case START_EVENT -> motd.startEvent();
            case SMP -> motd.smp();
            case MAINTENANCE -> motd.maintenance();
        };
    }
}
