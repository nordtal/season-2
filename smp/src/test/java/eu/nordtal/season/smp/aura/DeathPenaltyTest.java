package eu.nordtal.season.smp.aura;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class DeathPenaltyTest {

    private final DeathPenalty penalty = new DeathPenalty(5, 20, Set.of("lava", "cactus", "in_wall"));

    @Test
    void anOrdinaryDeathCostsFive() {
        assertEquals(-5, penalty.deltaFor("mob_attack", false));
        assertEquals(AuraReason.DEATH, penalty.reasonFor("mob_attack"));
    }

    @Test
    void aListedCauseCostsTwenty() {
        assertEquals(-20, penalty.deltaFor("lava", false));
        assertEquals(AuraReason.DEATH_LISTED, penalty.reasonFor("lava"));
    }

    @Test
    void theArenaCostsNothingAtAll() {
        // The stake of plus or minus 10 already decides a duel.
        assertEquals(0, penalty.deltaFor("player_attack", true));
        assertEquals(0, penalty.deltaFor("lava", true), "even a listed cause, inside the arena");
    }

    @Test
    void aNamespaceAndCaseAreBothIgnored() {
        // A rule with a typed namespace would stop matching without any visible sign.
        assertEquals(-20, penalty.deltaFor("minecraft:lava", false));
        assertEquals(-20, penalty.deltaFor("LAVA", false));
        assertEquals(-20, penalty.deltaFor("  Minecraft:Lava  ", false));
    }

    @Test
    void aDeathWithNoKnownCauseIsAnOrdinaryOne() {
        assertEquals(-5, penalty.deltaFor(null, false));
        assertFalse(penalty.isListed(null));
    }

    @Test
    void theBorderAndTheVoidAndTheDragonFightAllCost() {
        assertEquals(-5, penalty.deltaFor("outside_border", false));
        assertEquals(-5, penalty.deltaFor("out_of_world", false));
        assertEquals(-5, penalty.deltaFor("dragon_breath", false));
    }

    @Test
    void aNegativelyConfiguredPenaltyIsRefused() {
        // The class subtracts the penalty, so a negative one would pay a player for dying.
        assertThrows(IllegalArgumentException.class, () -> new DeathPenalty(-5, 20, Set.of()));
    }

    @Test
    void anEmptyListedSetLeavesOneRuleForEverything() {
        final DeathPenalty flat = new DeathPenalty(5, 20, Set.of());

        assertEquals(-5, flat.deltaFor("lava", false));
        assertTrue(flat.listedCauses().isEmpty());
    }
}
