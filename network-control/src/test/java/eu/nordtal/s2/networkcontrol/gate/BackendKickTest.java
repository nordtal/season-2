package eu.nordtal.s2.networkcontrol.gate;

import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent.DisconnectPlayer;

import net.kyori.adventure.text.Component;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The decision on its own - see {@link BackendKick} for why it has to be static to be testable at
 * all, and see {@link BackendKickLoopTest} for what {@link BackendKick.Decision#TO_LIMBO} is for.
 */
class BackendKickTest {

    private static final Component REASON =
            Component.text("The server cannot reach its database right now.");

    @Test
    @DisplayName("a disconnect with a reason shows the backend's own component")
    void aDisconnectWithAReasonIsShown() {
        assertEquals(BackendKick.Decision.SHOW_REASON,
                BackendKick.decide(DisconnectPlayer.create(Component.text(
                        "Kicked whilst connecting to smp: ...")), REASON));
    }

    @Test
    @DisplayName("a kick with no reason at all goes to the waiting room, not the disconnect screen")
    void aKickWithNoReasonGoesToLimbo() {
        // season-2-ops/20: the backend gave nothing to show, which - checked against the running
        // proxy's own velocity-4.2.0-30.jar, 2026-09-16 - only happens when the connection died
        // rather than being kicked on purpose. That is the "the network is still standing" case,
        // and the fix is to keep the player on the network instead of losing them to a disconnect
        // screen.
        assertEquals(BackendKick.Decision.TO_LIMBO, BackendKick.decide(
                DisconnectPlayer.create(Component.text("Unable to connect to smp.")), null));
    }

    @Test
    @DisplayName("a result that is not a disconnect is left alone, so nobody is moved")
    void everyOtherResultIsUntouched() {
        // Notify: the player is already on another server and stays there. Redirect: something else
        // has chosen where they go. Turning either into a disconnect or a limbo trip would be a
        // routing change dressed up as a wording change, which is the one thing this class must not
        // do - whatever the reason says.
        assertEquals(BackendKick.Decision.LEAVE,
                BackendKick.decide(KickedFromServerEvent.Notify.create(REASON), REASON));
        assertEquals(BackendKick.Decision.LEAVE,
                BackendKick.decide(KickedFromServerEvent.Notify.create(REASON), null));
    }
}
