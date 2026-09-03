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
 * The season 2 login decision, per docs/access-system.md and docs/season-phases.md: one call to
 * {@code AccessDirectory#accessState}, then linked? member and not banned? and finally whatever the
 * <b>current phase</b> asks on top - each branch with its own disconnect screen. If the database
 * itself could not be reached, the fallback cache stands in for all of it.
 *
 * <h2>The phase is part of the decision, since 2026-08-31</h2>
 * This class used to refuse every linked member without active access, unconditionally - finding 1
 * in docs/state-of-play.md, i.e. it behaved as though the network were permanently in
 * {@code SMP} and a {@code PRE_EVENT} network would have refused everyone who had not paid.
 * docs/season-phases.md's phase table is what it now walks:
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
 * <h2>Maintenance no longer refuses anybody here, decided 2026-08-31</h2>
 * This class used to deny every non-admin during {@code MAINTENANCE} with {@code gate.maintenance}.
 * docs/season-phases.md left "disconnect <b>or</b> hold in limbo" open while its own phase table
 * already said non-admins land in {@code limbo}; the owner settled it on holding them. The gate now
 * lets them onto the proxy and {@code eu.nordtal.s2.networkcontrol.routing.PlayerRouter} puts them
 * in {@code limbo}, which is where the explanation is shown. An <b>unlinked</b> player is still
 * refused with a link code, in maintenance as in every other phase - that half was not reversed.
 *
 * <p>
 * The phase arrives on the <b>same row</b> as the access state ({@link AccessState#phase()}):
 * docs/season-phases.md pins the login path to a single round trip, so there is deliberately no
 * call to {@code PhaseDirectory#currentPhase()} anywhere in this class. {@code PhaseWatch}'s poll
 * and {@code LISTEN} exist for everything that is <em>not</em> a login.
 * </p>
 * <p>
 * The table itself lives in {@link GateOutcome}, which is a total function of the one record the
 * login query returns and can therefore be tested exhaustively without a proxy. It is not
 * {@link AccessState#mayJoin()} because each branch needs a different screen; {@code mayJoin()} is
 * the same table collapsed to one boolean, and is what the fallback cache and the expiry sweep use.
 * </p>
 * <p>
 * {@code @Subscribe} handlers are asynchronous by default in Velocity 4 (see
 * {@code com.velocitypowered.api.event.Subscribe#async}), so the blocking JDBC call this makes
 * does not run on a Netty I/O thread. How long that call is allowed to block is not this class's
 * concern - it is the connection pool's, configured with a short {@code query-timeout-seconds} in
 * {@code database.yml} so a struggling database fails fast onto the fallback path rather than
 * queueing logins behind it.
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

        // Written on every successful query, healthy path or not - see FallbackCache for why an
        // access-inactive state still has to go through here: it evicts a now-stale positive entry.
        fallback.remember(uuid, state);
        // And the facts the /phase command and the play-time writer need, from the same row.
        roster.remember(uuid, state);

        final Instant countdownFrom = state.phase() == SeasonPhase.PRE_LAUNCH ? clock.instant() : null;

        switch (GateOutcome.of(state)) {
            // ALLOW leaves the event's own default result (ComponentResult.allowed()) standing -
            // unless the network is full, which is the one refusal that is not about this player at
            // all. Where they then land is PlayerRouter's question, and in MAINTENANCE the answer
            // is limbo - which is the whole of what that phase now does to a non-admin.
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
     * It is checked <b>after</b> the access decision rather than before it, which costs a database
     * round trip for a player who is then refused anyway. That is deliberate: the admin flag lives
     * on the row that query returns, and a full network that cannot be entered by the person who
     * has to go and fix it is the wrong kind of full. The row is worth one query.
     * </p>
     * <p>
     * The count is {@code proxy.getPlayerCount()} - every connected player, including the ones
     * still in the waiting room, because a slot they are holding is a slot. Two logins arriving in
     * the same instant can both see room and both take it: the limit is exceeded by one, nothing
     * breaks, and no reservation scheme is worth what it would cost to prevent a number nobody can
     * observe. {@code >=} rather than {@code >} because the player being decided about is not in
     * the count yet.
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
     * The database answered "unlinked", which is itself a healthy-path result - not the fallback
     * branch. Issuing the code is a second, separate database call, so it can still fail on its
     * own; that failure is treated the same as the database being unreachable in the first place,
     * because there is no code to show either way.
     * <p>
     * Note that this happens in every phase, {@code MAINTENANCE} included, and that it is the one
     * refusal maintenance still produces. An unlinked player cannot be held in {@code limbo} in any
     * useful way - there is nothing to wait for, because linking happens in Discord, not here - so
     * handing them the code they will need anyway costs one statement and saves them a second
     * wasted attempt later.
     * </p>
     */
    private void issueCodeAndDeny(final LoginEvent event, final Player player, final UUID uuid,
                                  final Instant launch, final Instant now) {
        try {
            final LinkCode code = access.issueLinkCode(uuid, Duration.ofMinutes(config.linkCodeTtlMinutes()));
            event.setResult(ComponentResult.denied(messages.notLinked(code.code(), launch, now)));
        } catch (final RuntimeException exception) {
            logger.error("Could not issue a link code for {} ({})", uuid, player.getUsername(), exception);
            // The player's language is unknown either way at this point (that is exactly why the
            // unlinked screen shows both languages), so English is as good a guess as any here.
            event.setResult(ComponentResult.denied(messages.trouble(Locale.ENGLISH)));
        }
    }

    /**
     * Only a player the cache remembers as having been allowed in when it was cached gets in;
     * everyone else - including every player the cache has simply never heard of - is refused.
     * <p>
     * The cache stores the outcome of {@link AccessState#mayJoin()}, which is phase-aware, so what
     * it remembers is "this player was let in, in the phase that was current at the time". The
     * phase cannot be re-read here either - it lives in the same unreachable database - and
     * docs/season-phases.md's rule for that case is the last known phase, which is exactly the one
     * the cached decision was made under.
     * </p>
     */
    private void fallBackToCache(final LoginEvent event, final UUID uuid) {
        if (fallback.mayJoin(uuid)) {
            // The player limit still applies. Nothing about it needs the database - the count is
            // the proxy's own and the limit is a config value - so an outage must not become a way
            // past it. Nobody is exempt on this path: the admin flag is exactly what could not be
            // read, and guessing it would be guessing in the permissive direction.
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
