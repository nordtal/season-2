package eu.nordtal.s2.common.access;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole of {@link AdminOperators}, without a server - which is the reason it is shaped the way
 * it is.
 *
 * <p>Two of these are not about behaviour but about the disk. {@code setOp} writes
 * {@code ops.json}, and {@link AdminOperators#refresh} is built to be called on every poll tick of
 * the admin watcher; if a repeated call re-asserted the state, the file would be rewritten on a
 * timer for as long as the server runs. So "a call that changes nothing writes nothing" is an
 * assertion here, not an implementation detail.</p>
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
    @DisplayName("an admin who joins becomes an operator, an ordinary player does not")
    void joinGrantsOnlyToAdmins() {
        operators.onJoin(ADMIN, true);
        operators.onJoin(PLAYER, false);

        assertEquals(Set.of(ADMIN), ops.operators());
        assertTrue(operators.holds(ADMIN));
        assertFalse(operators.holds(PLAYER));
    }

    @Test
    @DisplayName("quitting removes the operator this object granted")
    void quitRemoves() {
        operators.onJoin(ADMIN, true);
        operators.onQuit(ADMIN);

        assertEquals(Set.of(), ops.operators());
        assertFalse(operators.holds(ADMIN));
    }

    @Test
    @DisplayName("a quit by somebody who was never opped writes nothing")
    void quitByNonAdminIsFree() {
        operators.onJoin(PLAYER, false);
        ops.calls.clear();

        operators.onQuit(PLAYER);

        assertEquals(List.of(), ops.calls);
    }

    @Test
    @DisplayName("the sweep removes every operator, including ones this object never granted")
    void sweepRemovesEverybody() {
        // What a crash between join and quit leaves behind: a name in ops.json that no running
        // object knows about. This is the case the sweep exists for.
        final UUID leftBehind = UUID.randomUUID();
        ops.setOp(leftBehind, true);
        operators.onJoin(ADMIN, true);

        operators.sweep();

        assertEquals(Set.of(), ops.operators());
        assertFalse(operators.holds(ADMIN));
    }

    @Test
    @DisplayName("the sweep asks nothing about who is an admin")
    void sweepConsultsNoAdminList() {
        // Stated as a test because it is the whole reason the sweep is safe to run at enable, when
        // the database may not be reachable at all: there is no admin set to pass in, and the
        // signature is what guarantees it.
        ops.setOp(ADMIN, true);

        operators.sweep();

        assertEquals(Set.of(), ops.operators());
    }

    @Test
    @DisplayName("refresh grants to an admin online and takes it from one who is not")
    void refreshDerivesFromTheAdminSet() {
        operators.onJoin(ADMIN, true);
        operators.onJoin(PLAYER, false);

        // The revocation case: ADMIN lost the Discord role while online, PLAYER gained it.
        operators.refresh(Set.of(PLAYER), Set.of(ADMIN, PLAYER));

        assertEquals(Set.of(PLAYER), ops.operators());
    }

    @Test
    @DisplayName("refresh does not touch somebody who is not online")
    void refreshIgnoresTheOffline() {
        operators.onJoin(ADMIN, true);
        ops.calls.clear();

        operators.refresh(Set.of(), Set.of());

        assertEquals(List.of(), ops.calls);
        assertTrue(operators.holds(ADMIN));
    }

    @Test
    @DisplayName("a refresh that changes nothing writes nothing")
    void repeatedRefreshIsFree() {
        // The poll tick. Without this property ops.json is rewritten on a timer forever.
        operators.onJoin(ADMIN, true);
        ops.calls.clear();

        operators.refresh(Set.of(ADMIN), Set.of(ADMIN));
        operators.refresh(Set.of(ADMIN), Set.of(ADMIN));
        operators.refresh(Set.of(ADMIN), Set.of(ADMIN));

        assertEquals(List.of(), ops.calls);
    }

    @Test
    @DisplayName("opping the same admin twice writes once")
    void joinIsIdempotent() {
        operators.onJoin(ADMIN, true);
        ops.calls.clear();

        operators.onJoin(ADMIN, true);

        assertEquals(List.of(), ops.calls);
    }

    @Test
    @DisplayName("a revoked admin who rejoins as an admin is opped again")
    void grantSurvivesARoundTrip() {
        operators.onJoin(ADMIN, true);
        operators.refresh(Set.of(), Set.of(ADMIN));
        assertFalse(operators.holds(ADMIN));

        operators.refresh(Set.of(ADMIN), Set.of(ADMIN));

        assertEquals(Set.of(ADMIN), ops.operators());
    }

    @Test
    @DisplayName("held is a copy, not the live set")
    void heldIsACopy() {
        operators.onJoin(ADMIN, true);
        final Set<UUID> held = operators.held();

        operators.onQuit(ADMIN);

        assertEquals(Set.of(ADMIN), held);
    }
}
