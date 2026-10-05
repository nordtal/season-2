package eu.nordtal.season.hungergames.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Checks that the four ways a game can end stay distinct, since {@code Ceremony} branches on them. */
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
        record Shape(boolean hasWinner, boolean tie) {}

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
