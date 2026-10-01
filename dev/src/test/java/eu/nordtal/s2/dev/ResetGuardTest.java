package eu.nordtal.s2.dev;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ResetGuardTest {

    @Test
    void everyServerThatRunsOneOfOurPluginsIsResettable() {
        for (final String service : List.of("proxy", "limbo", "hunger-games", "smp")) {
            assertTrue(ResetGuard.known(service), service);
        }
    }

    @Test
    void noNameAnotherContainerAWildcardAndANearMissAreAllRefused() {
        for (final String wrong : List.of("", " ", "postgres", "steward", "all", "smp ", "SMP", "../smp", "*")) {
            assertFalse(ResetGuard.known(wrong), "'" + wrong + "' was accepted as a service to reset");
        }
    }

    @Test
    void theConfirmationHasToBeTheNameItself() {
        assertTrue(ResetGuard.confirmed("smp", "smp"));
        for (final String typed : List.of("", " ", "y", "Y", "yes", "YES", "SMP", "smp ", "limbo", "*")) {
            assertFalse(ResetGuard.confirmed("smp", typed), "'" + typed + "' was accepted as confirmation for smp");
        }
    }

    @Test
    void anEmptyTargetConfirmsNothing() {
        assertFalse(ResetGuard.confirmed("", ""), "an empty answer would delete on a bare Return");
    }
}
