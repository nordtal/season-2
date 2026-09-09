package eu.nordtal.s2.networkcontrol.gate;

import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.AccessState;
import eu.nordtal.s2.common.access.LinkCode;
import eu.nordtal.s2.networkcontrol.config.GateSpec;
import eu.nordtal.s2.networkcontrol.config.NetworkSpec;

import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * The season 2 login decision: one call to {@code AccessDirectory#accessState}, then linked? member
 * and not banned? and finally whatever the current phase asks on top - each branch with its own
 * disconnect screen. If the database could not be reached, the fallback cache stands in for all of
 * it.
 *
 * <table>
 *   <caption>What this class decides, per phase</caption>
 *   <tr><th>phase</th><th>who gets in</th><th>everyone else sees</th></tr>
 *   <tr><td>{@code PRE_EVENT}</td><td>linked member, not banned</td><td>-</td></tr>
 *   <tr><td>{@code START_EVENT}</td><td>linked member, not banned</td><td>-</td></tr>
 *   <tr><td>{@code SMP}</td><td>the above plus active access</td><td>{@code gate.no-access}</td></tr>
 *   <tr><td>{@code MAINTENANCE}</td><td>linked member, not banned</td><td>-</td></tr>
 * </table>
 *
 * <p>
 * Maintenance refuses nobody here: a non-admin is let onto the proxy and
 * {@code eu.nordtal.s2.networkcontrol.routing.PlayerRouter} puts them in {@code limbo}, where the
 * explanation is shown. An <b>unlinked</b> player is still refused with a link code, in every phase.
 * </p>
 * <p>
 * The phase arrives on the <b>same row</b> as the access state ({@link AccessState#phase()}): the
 * login path is one round trip, so there is deliberately no call to
 * {@code PhaseDirectory#currentPhase()} here. {@code PhaseWatch}'s poll and {@code LISTEN} exist for
 * everything that is <em>not</em> a login.
 * </p>
 * <p>
 * The table itself lives in {@link GateOutcome}. It is not {@link AccessState#mayJoin()} because
 * each branch needs a different screen.
 * </p>
 * <p>
 * {@code @Subscribe} handlers are asynchronous by default in Velocity 4, so the blocking JDBC call
 * does not run on a Netty I/O thread. How long it may block is the connection pool's concern,
 * bounded by {@code query-timeout-seconds} in {@code database.yml}.
 * </p>
 */
public final class LoginGate {

    private final Logger logger;
    private final ProxyServer proxy;
    private final AccessDirectory access;
    private final FallbackCache fallback;
    private final LoginRoster roster;
    private final GateMessages messages;
    private final GateSpec config;
    private final NetworkSpec network;
    private final Clock clock;

    public LoginGate(final Logger logger, final ProxyServer proxy, final AccessDirectory access,
                     final FallbackCache fallback, final LoginRoster roster, final GateMessages messages,
                     final GateSpec config, final NetworkSpec network, final Clock clock) {
        this.logger = logger;
        this.proxy = proxy;
        this.access = access;
        this.fallback = fallback;
        this.roster = roster;
        this.messages = messages;
        this.config = config;
        this.network = network;
        this.clock = clock;
    }

    @Subscribe
    public void onLogin(final LoginEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();

        final AccessState state;
        try {
            state = access.accessState(uuid);
        } catch (final RuntimeException exception) {
            logger.error("Could not reach the access database for {} ({}); falling back to the "
                    + "last-known-state cache", uuid, player.getUsername(), exception);
            fallBackToCache(event, uuid);
            return;
        }

        // Written on every successful query: an access-inactive state has to go through here too,
        // because it evicts a now-stale positive entry.
        fallback.remember(uuid, state);
        // And the facts the /phase command and the play-time writer need, from the same row.
        roster.remember(uuid, state);

        final Instant countdownFrom = state.phase() == SeasonPhase.PRE_LAUNCH ? clock.instant() : null;

        switch (GateOutcome.of(state)) {
            // ALLOW leaves the event's own default result standing, unless the network is full -
            // the one refusal that is not about this player at all. Where they land is
            // PlayerRouter's question.
            case ALLOW -> refuseIfFull(event, state);
            case NOT_LINKED -> issueCodeAndDeny(event, player, uuid, state.launch(), countdownFrom);
            case NOT_MEMBER -> event.setResult(ComponentResult.denied(messages.notMember(state.locale())));
            case NO_ACCESS -> event.setResult(ComponentResult.denied(messages.noAccess(state.locale())));
            case PRE_LAUNCH_BUY -> event.setResult(ComponentResult.denied(
                    messages.preLaunchBuy(state.locale(), state.launch(), countdownFrom)));
            case PRE_LAUNCH_READY -> event.setResult(ComponentResult.denied(
                    messages.preLaunchReady(state.locale(), state.launch(), countdownFrom)));
        }
    }

    /**
     * The network-wide player limit, and the only place it is enforced.
     * <p>
     * Checked <b>after</b> the access decision, deliberately: the admin flag lives on the row that
     * query returns, and a full network that the person who has to fix it cannot enter is the wrong
     * kind of full.
     * </p>
     * <p>
     * The count includes players still in the waiting room, because a slot they hold is a slot. Two
     * logins in the same instant can both see room and both take it; the limit is exceeded by one,
     * which is accepted rather than fixed with a reservation scheme.
     * </p>
     */
    private void refuseIfFull(final LoginEvent event, final AccessState state) {
        final int maximum = network.maxPlayers();
        final int online = proxy.getPlayerCount();
        if (online < maximum || state.admin()) {
            return;
        }
        logger.info("Refused {} - the network is full ({} of {})", state.minecraftAccount(), online, maximum);
        event.setResult(ComponentResult.denied(messages.full(state.locale(), online, maximum)));
    }

    /**
     * The database answered "unlinked", which is a healthy-path result. Issuing the code is a
     * second database call and can fail on its own; that failure is treated like the database being
     * unreachable, because there is no code to show either way.
     * <p>
     * This happens in every phase, {@code MAINTENANCE} included: an unlinked player cannot usefully
     * be held in {@code limbo}, since linking happens in Discord.
     * </p>
     */
    private void issueCodeAndDeny(final LoginEvent event, final Player player, final UUID uuid,
                                  final Instant launch, final Instant now) {
        try {
            final LinkCode code = access.issueLinkCode(uuid, Duration.ofMinutes(config.linkCodeTtlMinutes()));
            event.setResult(ComponentResult.denied(messages.notLinked(code.code(), launch, now)));
        } catch (final RuntimeException exception) {
            logger.error("Could not issue a link code for {} ({})", uuid, player.getUsername(), exception);
            // The player's language is unknown on this path, which is why the unlinked screen is
            // bilingual; English is as good a guess as any.
            event.setResult(ComponentResult.denied(messages.trouble(Locale.ENGLISH)));
        }
    }

    /**
     * Only a player the cache remembers as allowed gets in; everyone else, including anyone the
     * cache has never heard of, is refused.
     * <p>
     * The cache stores the outcome of {@link AccessState#mayJoin()}, which is phase-aware, so it
     * remembers "let in, under the phase current at the time". The phase cannot be re-read here -
     * it is in the same unreachable database - and the rule for that case is the last known phase.
     * </p>
     */
    private void fallBackToCache(final LoginEvent event, final UUID uuid) {
        if (fallback.mayJoin(uuid)) {
            // The player limit still applies: nothing about it needs the database, so an outage
            // must not become a way past it. Nobody is exempt here, because the admin flag is
            // exactly what could not be read.
            final int maximum = network.maxPlayers();
            final int online = proxy.getPlayerCount();
            if (online >= maximum) {
                event.setResult(ComponentResult.denied(
                        messages.full(fallback.localeOf(uuid), online, maximum)));
            }
            return; // default result stands: allowed
        }
        event.setResult(ComponentResult.denied(messages.trouble(fallback.localeOf(uuid))));
    }
}
