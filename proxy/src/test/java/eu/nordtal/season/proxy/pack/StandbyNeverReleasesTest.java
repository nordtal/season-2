package eu.nordtal.season.proxy.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.SeasonPhase;
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
        for (final SeasonPhase phase : SeasonPhase.values()) {
            for (final boolean admin : new boolean[] {false, true}) {
                final Optional<WaitReason> reason = LimboHold.reason(true, phase, admin, true, true, false, false);
                // MAINTENANCE for a non-admin outranks it, as the longer-lived truth.
                final WaitReason expected =
                        phase == SeasonPhase.MAINTENANCE && !admin ? WaitReason.MAINTENANCE : WaitReason.UPDATE;
                assertEquals(Optional.of(expected), reason, phase + ", admin=" + admin);
            }
        }
    }

    @Test
    void thePackStillComesFirst() {
        assertEquals(
                Optional.of(WaitReason.PACK),
                LimboHold.reason(false, SeasonPhase.SMP, false, true, true, false, false));
    }

    @Test
    void theLiveProxyIsUnchanged() {
        assertEquals(Optional.empty(), LimboHold.reason(true, SeasonPhase.SMP, false, false, true, false, false));
    }
}
