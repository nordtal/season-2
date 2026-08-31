package eu.nordtal.s2.hungergames.game;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TiebreakTest {

    private static final UUID FIRST = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SECOND = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void moreKillsWins() {
        final Optional<UUID> winner = Tiebreak.resolve(FIRST, 3, SECOND, 1);
        assertEquals(Optional.of(FIRST), winner);
    }

    @Test
    void orderDoesNotMatter() {
        final Optional<UUID> winner = Tiebreak.resolve(SECOND, 1, FIRST, 3);
        assertEquals(Optional.of(FIRST), winner);
    }

    @Test
    void equalKillsMeansNobodyWins() {
        assertTrue(Tiebreak.resolve(FIRST, 2, SECOND, 2).isEmpty());
    }

    @Test
    void zeroKillsEachIsStillATie() {
        assertTrue(Tiebreak.resolve(FIRST, 0, SECOND, 0).isEmpty());
    }
}
