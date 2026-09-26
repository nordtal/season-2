package eu.nordtal.s2.proxy.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.limbo.WaitReason;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Nobody is released from a standby proxy's waiting room.
 *
 * There is nothing to release them <em>to</em>. The backend the phase names is up and healthy
 * and completely beside the point: they are going home to the other proxy, not onward from this
 * one. Releasing them would connect them to a Paper server they are about to be pulled off again -
 * and for the ones parked out of {@code limbo}, it would rejoin them to the very backend they left
 * a second ago.
 */
class StandbyNeverReleasesTest {

    @Test
    void everybodyIsHeldOnAStandby() {
        for (final SeasonPhase phase : SeasonPhase.values()) {
            for (final boolean admin : new boolean[] {false, true}) {
                final Optional<WaitReason> reason = LimboHold.reason(true, phase, admin, true, true, false, false);
                // MAINTENANCE for a non-admin outranks it: the longer-lived truth, and what they were already reading.
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
