package eu.nordtal.season.smp.rail;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RiderCartPacketsTest {

    private final RiderCartPackets packets = new RiderCartPackets();
    private final UUID rider = UUID.randomUUID();

    @Test
    void theTeleportOfADrivenCartIsHeldBackFromItsRiderOnly() {
        packets.started(7, rider);

        assertTrue(packets.hides(7, rider));
        assertFalse(packets.hides(7, UUID.randomUUID()), "a bystander sees the cart as before");
        assertFalse(packets.hides(8, rider), "another entity's teleport reaches the rider");
        assertFalse(packets.hides(7, null), "a connection without a player yet is left alone");
    }

    @Test
    void anEndedRideLetsTheTeleportsThroughAgain() {
        packets.started(7, rider);
        packets.ended(7);

        assertFalse(packets.hides(7, rider));
    }
}
