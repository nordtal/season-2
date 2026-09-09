package eu.nordtal.s2.networkcontrol.gate;

import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent.DisconnectPlayer;

import net.kyori.adventure.text.Component;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A backend's own disconnect screen reaches the player, and nothing else about a kick changes.
 *
 * <p>The event itself cannot be built here - it needs a {@code Player} and a
 * {@code RegisteredServer}, which exist only on a running proxy - which is exactly why the decision
 * is a static function of its two inputs. What is asserted is the boundary: this replaces text and
 * must never move anybody, because where a kicked player ends up is a design question nobody has
 * answered yet.</p>
 */
class BackendKickTest {

    private static final Component REASON =
            Component.text("The server cannot reach its database right now.");

    @Test
    @DisplayName("a disconnect keeps the backend's own component instead of Velocity's wrapper")
    void aDisconnectShowsWhatTheBackendWrote() {
        final Optional<KickedFromServerEvent.ServerKickResult> replacement =
                BackendKick.unwrapped(DisconnectPlayer.create(Component.text(
                        "Kicked whilst connecting to smp: ...")), REASON);

        assertTrue(replacement.isPresent(), "Velocity's wrapper is the whole defect - it is English,"
                + " it names an internal server name, and it sits above the screen the backend"
                + " wrote carefully");
        assertEquals(REASON,
                assertInstanceOf(DisconnectPlayer.class, replacement.get()).getReasonComponent());
    }

    @Test
    @DisplayName("a kick with no reason keeps Velocity's, because ours would say less")
    void nothingIsSaidWhereTheBackendSaidNothing() {
        assertEquals(Optional.empty(), BackendKick.unwrapped(
                DisconnectPlayer.create(Component.text("Unable to connect to smp.")), null),
                "replacing a message that at least names the layer it came from with one of our own"
                        + " that says nothing is worse on the one path somebody has to diagnose"
                        + " from a screenshot");
    }

    @Test
    @DisplayName("a result that is not a disconnect is left alone, so nobody is moved")
    void everyOtherResultIsUntouched() {
        // Notify: the player is already on another server and stays there. Redirect: something else
        // has chosen where they go. Turning either into a disconnect would be a routing change
        // dressed up as a wording change, which is the one thing this class must not do.
        assertEquals(Optional.empty(),
                BackendKick.unwrapped(KickedFromServerEvent.Notify.create(REASON), REASON));
    }
}
