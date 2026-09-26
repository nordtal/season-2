package eu.nordtal.s2.common.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link AdminOperators} without a server.
 *
 * A call that changes nothing must write nothing, since {@code setOp} rewrites {@code ops.json}.
 */
class AdminOperatorsTest {

    /** Records every call, so a test can assert on writes that did <em>not</em> happen. */
    private static final class RecordingOps implements AdminOperators.Ops {

        private final Set<UUID> operators = new LinkedHashSet<>();
        private final List<String> calls = new ArrayList<>();

        @Override
        public void setOp(final UUID player, final boolean operator) {
            calls.add((operator ? "+" : "-") + player);
            if (operator) {
                operators.add(player);
            } else {
                operators.remove(player);
            }
        }

        @Override
        public Set<UUID> operators() {
            return operators;
        }
    }

    private final RecordingOps ops = new RecordingOps();
    private final AdminOperators operators = new AdminOperators(ops);

    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID PLAYER = UUID.randomUUID();

    @Test
    void anAdminWhoJoinsBecomesAnOperatorAnOrdinaryPlayerDoesNot() {
        operators.onJoin(ADMIN, true);
        operators.onJoin(PLAYER, false);

        assertEquals(Set.of(ADMIN), ops.operators());
        assertTrue(operators.holds(ADMIN));
        assertFalse(operators.holds(PLAYER));
    }

    @Test
    void quittingRemovesTheOperatorThisObjectGranted() {
        operators.onJoin(ADMIN, true);
        operators.onQuit(ADMIN);

        assertEquals(Set.of(), ops.operators());
        assertFalse(operators.holds(ADMIN));
    }

    @Test
    void aQuitBySomebodyWhoWasNeverOppedWritesNothing() {
        operators.onJoin(PLAYER, false);
        ops.calls.clear();

        operators.onQuit(PLAYER);

        assertEquals(List.of(), ops.calls);
    }

    @Test
    void theSweepRemovesEveryOperatorIncludingOnesThisObjectNeverGranted() {
        // A crash between join and quit leaves a name in ops.json that no object knows about.
        final UUID leftBehind = UUID.randomUUID();
        ops.setOp(leftBehind, true);
        operators.onJoin(ADMIN, true);

        operators.sweep();

        assertEquals(Set.of(), ops.operators());
        assertFalse(operators.holds(ADMIN));
    }

    @Test
    void theSweepAsksNothingAboutWhoIsAnAdmin() {
        // The sweep takes no admin set, so it is safe at enable when the database may be unreachable.
        ops.setOp(ADMIN, true);

        operators.sweep();

        assertEquals(Set.of(), ops.operators());
    }

    @Test
    void refreshGrantsToAnAdminOnlineAndTakesItFromOneWhoIsNot() {
        operators.onJoin(ADMIN, true);
        operators.onJoin(PLAYER, false);

        // The revocation case: ADMIN lost the Discord role while online, PLAYER gained it.
        operators.refresh(Set.of(PLAYER), Set.of(ADMIN, PLAYER));

        assertEquals(Set.of(PLAYER), ops.operators());
    }

    @Test
    void refreshDoesNotTouchSomebodyWhoIsNotOnline() {
        operators.onJoin(ADMIN, true);
        ops.calls.clear();

        operators.refresh(Set.of(), Set.of());

        assertEquals(List.of(), ops.calls);
        assertTrue(operators.holds(ADMIN));
    }

    @Test
    void aRefreshThatChangesNothingWritesNothing() {
        // The poll tick. Without this property ops.json is rewritten on a timer forever.
        operators.onJoin(ADMIN, true);
        ops.calls.clear();

        operators.refresh(Set.of(ADMIN), Set.of(ADMIN));
        operators.refresh(Set.of(ADMIN), Set.of(ADMIN));
        operators.refresh(Set.of(ADMIN), Set.of(ADMIN));

        assertEquals(List.of(), ops.calls);
    }

    @Test
    void oppingTheSameAdminTwiceWritesOnce() {
        operators.onJoin(ADMIN, true);
        ops.calls.clear();

        operators.onJoin(ADMIN, true);

        assertEquals(List.of(), ops.calls);
    }

    @Test
    void aRevokedAdminWhoRejoinsAsAnAdminIsOppedAgain() {
        operators.onJoin(ADMIN, true);
        operators.refresh(Set.of(), Set.of(ADMIN));
        assertFalse(operators.holds(ADMIN));

        operators.refresh(Set.of(ADMIN), Set.of(ADMIN));

        assertEquals(Set.of(ADMIN), ops.operators());
    }

    @Test
    void heldIsACopyNotTheLiveSet() {
        operators.onJoin(ADMIN, true);
        final Set<UUID> held = operators.held();

        operators.onQuit(ADMIN);

        assertEquals(Set.of(ADMIN), held);
    }
}
