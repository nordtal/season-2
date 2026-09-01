package eu.nordtal.s2.hungergames.game;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the four ways a game can end stay four ways.
 *
 * <p>They were three until 2026-09-01, and effectively two: {@code Outcome} carried a {@code tie}
 * flag that {@code Outcome::win} always set to {@code false} - including for a win the tiebreaker
 * had just produced - and no caller read it. The ceremony therefore announced a kill-count decision
 * as an ordinary victory, in front of the players who had just watched both of them die together,
 * while {@code hg.win.tie-broken} and {@code hg.win.no-winner} sat written and translated in both
 * language files with nothing able to reach them.
 *
 * <p>{@code Ceremony} itself needs a world and real players, so what can be pinned here is the
 * shape it branches on. That is the part that was wrong.</p>
 */
class WinOutcomeTest {

    private static final UUID WINNER = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void anOrdinaryWinIsNotATie() {
        final WinTracker.Outcome outcome = WinTracker.Outcome.win(WINNER);

        assertEquals(WINNER, outcome.winnerMemberId());
        assertFalse(outcome.tie(), "one player left standing is not a tiebreak");
        assertEquals(0, outcome.winnerKills());
        assertEquals(0, outcome.loserKills());
    }

    @Test
    void aTiebreakWinHasBothAWinnerAndTheTieFlag() {
        final WinTracker.Outcome outcome = WinTracker.Outcome.tieBroken(WINNER, 3, 2);

        assertNotNull(outcome.winnerMemberId());
        assertTrue(outcome.tie(), "this is the case the ceremony has to word differently");
        assertEquals(3, outcome.winnerKills());
        assertEquals(2, outcome.loserKills());
    }

    @Test
    void aTieWithNoWinnerCarriesTheSharedKillCount() {
        final WinTracker.Outcome outcome = WinTracker.Outcome.tieNoWinner(2);

        assertNull(outcome.winnerMemberId());
        assertTrue(outcome.tie());
        // hg.win.no-winner prints "({kills} each)", so both sides have to be the same number.
        assertEquals(2, outcome.winnerKills());
        assertEquals(2, outcome.loserKills());
    }

    @Test
    void everybodyDeadWithNoSimultaneousPairIsNotATie() {
        final WinTracker.Outcome outcome = WinTracker.Outcome.noWinner();

        assertNull(outcome.winnerMemberId());
        assertFalse(outcome.tie(), "nothing was compared, so there was no tiebreak to announce");
    }

    @Test
    void theFourEndingsAreDistinguishableByTheTwoFieldsTheCeremonyBranchesOn() {
        record Shape(boolean hasWinner, boolean tie) {
        }

        final java.util.Set<Shape> shapes = new java.util.HashSet<>();
        for (final WinTracker.Outcome outcome : java.util.List.of(
                WinTracker.Outcome.win(WINNER),
                WinTracker.Outcome.tieBroken(WINNER, 3, 2),
                WinTracker.Outcome.tieNoWinner(2),
                WinTracker.Outcome.noWinner())) {
            shapes.add(new Shape(outcome.winnerMemberId() != null, outcome.tie()));
        }
        assertEquals(4, shapes.size(), "two endings that look the same get the same announcement");
    }
}
