package eu.nordtal.s2.smp.board;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** That a mistyped board kind in {@code config.yml} is caught rather than silently drawn as one. */
class BoardKindTest {

    @Test
    void bothKindsParseInAnyCase() {
        assertEquals(BoardKind.OBJECTIVE, BoardKind.parse("OBJECTIVE").orElseThrow());
        assertEquals(BoardKind.OBJECTIVE, BoardKind.parse("objective").orElseThrow());
        assertEquals(BoardKind.AURA, BoardKind.parse("  Aura  ").orElseThrow());
    }

    @Test
    void anythingElseIsEmptyAndNotAGuess() {
        assertTrue(BoardKind.parse("leaderboard").isEmpty());
        assertTrue(BoardKind.parse("").isEmpty());
        assertTrue(BoardKind.parse(null).isEmpty());
    }

    @Test
    void everyKindHasAMessageKey() {
        assertEquals("smp.board.objective.title", BoardKind.OBJECTIVE.messageKey());
        assertEquals("smp.board.aura.title", BoardKind.AURA.messageKey());
    }
}
