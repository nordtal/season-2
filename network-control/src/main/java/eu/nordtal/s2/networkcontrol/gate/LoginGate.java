package eu.nordtal.s2.networkcontrol.gate;

import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;

import eu.nordtal.s2.common.access.AccessDirectory;
import eu.nordtal.s2.common.access.AccessState;
import eu.nordtal.s2.common.access.LinkCode;
import eu.nordtal.s2.networkcontrol.config.GateSpec;

import org.slf4j.Logger;

import java.time.Duration;
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
    private final AccessDirectory access;
    private final FallbackCache fallback;
    private final LoginRoster roster;
    private final GateMessages messages;
    private final GateSpec config;

    public LoginGate(final Logger logger, final AccessDirectory access, final FallbackCache fallback,
                     final LoginRoster roster, final GateMessages messages, final GateSpec config) {
        this.logger = logger;
        this.access = access;
        this.fallback = fallback;
        this.roster = roster;
        this.messages = messages;
        this.config = config;
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

        switch (GateOutcome.of(state)) {
            // ALLOW leaves the event's own default result (ComponentResult.allowed()) standing.
            // Where the player then lands is PlayerRouter's question, and in MAINTENANCE the answer
            // is limbo - which is the whole of what that phase now does to a non-admin.
            case ALLOW -> { }
            case NOT_LINKED -> issueCodeAndDeny(event, player, uuid);
            case NOT_MEMBER -> event.setResult(ComponentResult.denied(messages.notMember(state.locale())));
            case NO_ACCESS -> event.setResult(ComponentResult.denied(messages.noAccess(state.locale())));
        }
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
    private void issueCodeAndDeny(final LoginEvent event, final Player player, final UUID uuid) {
        try {
            final LinkCode code = access.issueLinkCode(uuid, Duration.ofMinutes(config.linkCodeTtlMinutes()));
            event.setResult(ComponentResult.denied(messages.notLinked(code.code())));
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
            return; // default result stands: allowed
        }
        event.setResult(ComponentResult.denied(messages.trouble(fallback.localeOf(uuid))));
    }
}
