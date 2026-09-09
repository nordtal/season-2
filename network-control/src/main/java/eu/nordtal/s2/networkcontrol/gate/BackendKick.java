package eu.nordtal.s2.networkcontrol.gate;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;

import net.kyori.adventure.text.Component;

import java.util.Optional;

/**
 * A backend's own disconnect screen, shown as the backend wrote it.
 *
 * <h2>What a player saw instead</h2>
 * Velocity wraps a backend's kick reason in one of its own sentences before it puts it on the
 * screen - <i>"Kicked whilst connecting to smp: ..."</i> and <i>"Unable to connect to ..."</i>. Both
 * are English, both are Velocity's translation bundle rather than ours, and neither can be reached
 * from a message bundle in this repository. So the one screen a backend writes carefully -
 * {@code smp.error.database-unreachable}, the whole point of which is to tell a player their
 * progress is safe - arrived underneath a line naming an internal server name in the wrong
 * language.
 *
 * <h2>What this changes, and what it deliberately does not</h2>
 * <b>The text, and nothing else.</b> It only acts when Velocity has <em>already</em> decided to
 * disconnect this player, and it replaces that result with the same kind of result carrying the
 * backend's own component. A {@code Notify} (the player is on another server and stays there) and a
 * {@code RedirectPlayer} (something else has chosen where they go) are left exactly as they are.
 *
 * <p>That boundary is the point. <b>Where a kicked player ends up is a design question this class
 * does not answer</b> - whether being thrown off the SMP should put somebody back in the waiting
 * room rather than on a disconnect screen is a decision for the owner, and it is in the report
 * under "Zu bestätigen" rather than taken here quietly. Changing a result from
 * {@code Notify} to {@code DisconnectPlayer}, or the reverse, would move players.</p>
 *
 * <h2>Why there is no fallback text</h2>
 * A kick with no reason at all keeps Velocity's, deliberately. Our own "something went wrong"
 * sentence there would replace a message that at least says which layer produced it with one that
 * says nothing, and this path is exactly the one somebody has to diagnose from a screenshot.
 */
public final class BackendKick {

    /**
     * @param event Velocity's kick event, already carrying the result it was going to use
     */
    @Subscribe
    public void onKickedFromServer(final KickedFromServerEvent event) {
        unwrapped(event.getResult(), event.getServerKickReason().orElse(null))
                .ifPresent(event::setResult);
    }

    /**
     * The whole decision, as a function of the two things it depends on.
     *
     * <p>Static and separate because a {@link KickedFromServerEvent} cannot be built without a
     * {@code Player} and a {@code RegisteredServer}, which exist only on a running proxy - so the
     * handler above can be exercised nowhere and this can be exercised everywhere. The three ways
     * to get this wrong are all in here: acting on a result that is not a disconnect (which moves
     * players), acting with no reason to show (which replaces a message with nothing), and not
     * acting at all.</p>
     *
     * @param result what Velocity was going to do
     * @param reason the backend's own kick component, or {@code null} if it sent none
     * @return the result to use instead, or empty to leave Velocity's alone
     */
    static Optional<KickedFromServerEvent.ServerKickResult> unwrapped(
            final KickedFromServerEvent.ServerKickResult result, final Component reason) {
        if (!(result instanceof KickedFromServerEvent.DisconnectPlayer) || reason == null) {
            return Optional.empty();
        }
        return Optional.of(KickedFromServerEvent.DisconnectPlayer.create(reason));
    }
}
