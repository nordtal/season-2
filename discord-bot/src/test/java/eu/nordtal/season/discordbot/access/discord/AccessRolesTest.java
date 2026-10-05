package eu.nordtal.season.discordbot.access.discord;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.discordbot.access.discord.AccessRoles.Change;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Which access and donor roles a member holds while the onboarding's lock withholds them, and once it lifts. */
class AccessRolesTest {

    @Test
    void aLockedMemberIsNotGivenTheAccessRoleTheirGrantCovers() {
        assertAll(
                () -> assertEquals(Change.KEEP, AccessRoles.accessChange(true, true, false)),
                () -> assertEquals(Change.TAKE, AccessRoles.accessChange(true, true, true)));
    }

    @Test
    void anUnlockedMemberHoldsTheAccessRoleExactlyWhileAGrantCoversThem() {
        assertAll(
                () -> assertEquals(Change.GIVE, AccessRoles.accessChange(true, false, false)),
                () -> assertEquals(Change.KEEP, AccessRoles.accessChange(true, false, true)),
                () -> assertEquals(Change.TAKE, AccessRoles.accessChange(false, false, true)),
                () -> assertEquals(Change.KEEP, AccessRoles.accessChange(false, false, false)));
    }

    @Test
    void theLockTakesTheDonorRoleAndTheFlagGivesItBackOnceItLifts() {
        assertAll(
                () -> assertEquals(Change.TAKE, AccessRoles.donorChange(true, true, true)),
                () -> assertEquals(Change.KEEP, AccessRoles.donorChange(true, true, false)),
                () -> assertEquals(Change.GIVE, AccessRoles.donorChange(true, false, false)));
    }

    @Test
    void withNothingWithheldTheDonorRoleIsNeverTaken() {
        // A role an admin handed out by hand stays, and one the flag does not name is not given.
        assertAll(
                () -> assertEquals(Change.KEEP, AccessRoles.donorChange(false, false, true)),
                () -> assertEquals(Change.KEEP, AccessRoles.donorChange(false, false, false)),
                () -> assertEquals(Change.KEEP, AccessRoles.donorChange(true, false, true)));
    }

    @Test
    void theReconcileHandsNoLockedMemberTheAccessRoleAndTakesItFromOne() {
        final AccessRoles.Reconciled reconciled = AccessRoles.reconciled(
                Set.of("1", "2", "3"), List.of("2", "4"), id -> !id.equals("3"), Set.of("1", "2")::contains);

        assertAll(
                () -> assertEquals(Set.of(), reconciled.give(), "1 is locked and 3 is not in the guild"),
                () -> assertEquals(Set.of("2", "4"), reconciled.take(), "2 is locked and 4 has no grant"));
    }

    @Test
    void withNobodyLockedTheReconcileMirrorsTheGrants() {
        final AccessRoles.Reconciled reconciled =
                AccessRoles.reconciled(Set.of("1", "2", "3"), List.of("2", "4"), id -> !id.equals("3"), id -> false);

        assertAll(
                () -> assertEquals(Set.of("1"), reconciled.give()), () -> assertEquals(Set.of("4"), reconciled.take()));
    }
}
