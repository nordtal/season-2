package eu.nordtal.season.proxy.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent.DisconnectPlayer;
import eu.nordtal.season.proxy.NetworkServers;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

/** The decision on its own. */
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
        // No reason means the connection died; the network is still standing.
        assertEquals(
                BackendKick.Decision.TO_LIMBO,
                BackendKick.decide(DisconnectPlayer.create(Component.text("Unable to connect to smp.")), null));
    }

    @Test
    void everyOtherResultIsUntouched() {
        // Turning Notify or Redirect into a disconnect would change routing, not wording.
        assertEquals(
                BackendKick.Decision.LEAVE, BackendKick.decide(KickedFromServerEvent.Notify.create(REASON), REASON));
        assertEquals(BackendKick.Decision.LEAVE, BackendKick.decide(KickedFromServerEvent.Notify.create(REASON), null));
    }

    @Test
    void aRedirectIsNeverRewrittenToTheRoomThatJustKickedThePlayer() {
        final NetworkServers servers = new NetworkServers("limbo", "limbo-standby", "hunger-games", "smp");
        // The run counts limbo as stopping, and limbo-standby has just stopped under the player.
        assertNull(
                BackendKick.spareRoom("limbo-standby", "limbo", servers, "limbo"::equals, name -> true),
                "sending the player back into the room that dropped them loops until the client times out");
        assertEquals(
                "limbo-standby",
                BackendKick.spareRoom("smp", "limbo", servers, "limbo"::equals, name -> true),
                "a player kicked from elsewhere still goes to the room the run spares");
        assertNull(
                BackendKick.spareRoom("limbo-standby", "smp", servers, "limbo"::equals, name -> true),
                "a redirect to a server the run leaves alone stays as it is");
    }
}
