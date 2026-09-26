package eu.nordtal.s2.proxy.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent.DisconnectPlayer;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

/**
 * The decision on its own.
 *
 * See {@link BackendKick} for why it has to be static to be testable at all, and see {@link BackendKickLoopTest}
 * for what {@link BackendKick.Decision#TO_LIMBO} is for.
 */
class BackendKickTest {

    private static final Component REASON = Component.text("The server cannot reach its database right now.");

    @Test
    void aDisconnectWithAReasonIsShown() {
        assertEquals(
                BackendKick.Decision.SHOW_REASON,
                BackendKick.decide(
                        DisconnectPlayer.create(Component.text("Kicked whilst connecting to smp: ...")), REASON));
    }

    @Test
    void aKickWithNoReasonGoesToLimbo() {
        // No reason means the connection died rather than a deliberate kick; the network is still standing.
        assertEquals(
                BackendKick.Decision.TO_LIMBO,
                BackendKick.decide(DisconnectPlayer.create(Component.text("Unable to connect to smp.")), null));
    }

    @Test
    void everyOtherResultIsUntouched() {
        // Turning Notify or Redirect into a disconnect or limbo trip would be a routing change dressed as wording.
        assertEquals(
                BackendKick.Decision.LEAVE, BackendKick.decide(KickedFromServerEvent.Notify.create(REASON), REASON));
        assertEquals(BackendKick.Decision.LEAVE, BackendKick.decide(KickedFromServerEvent.Notify.create(REASON), null));
    }
}
