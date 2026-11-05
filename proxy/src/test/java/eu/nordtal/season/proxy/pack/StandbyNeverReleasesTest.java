package eu.nordtal.season.proxy.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.limboprotocol.WaitReason;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Nobody is released from a standby proxy's waiting room.
 *
 * They are going home to the other proxy, so any backend here is one they would be pulled off again.
 */
class StandbyNeverReleasesTest {

    @Test
    void everybodyIsHeldOnAStandby() {
        for (final SeasonHold hold : SeasonHold.values()) {
            for (final boolean admin : new boolean[] {false, true}) {
                final Optional<WaitReason> reason = LimboHold.reason(true, hold, admin, true, true, false, false);
                // MAINTENANCE for a non-admin outranks it, as the longer-lived truth.
                final WaitReason expected =
                        hold == SeasonHold.MAINTENANCE && !admin ? WaitReason.MAINTENANCE : WaitReason.UPDATE;
                assertEquals(Optional.of(expected), reason, hold + ", admin=" + admin);
            }
        }
    }

    @Test
    void thePackStillComesFirst() {
        assertEquals(
                Optional.of(WaitReason.PACK),
                LimboHold.reason(false, SeasonHold.NONE, false, true, true, false, false));
    }

    @Test
    void theLiveProxyIsUnchanged() {
        assertEquals(Optional.empty(), LimboHold.reason(true, SeasonHold.NONE, false, false, true, false, false));
    }
}
