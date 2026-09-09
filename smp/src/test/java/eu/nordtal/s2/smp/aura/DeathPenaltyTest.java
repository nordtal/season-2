package eu.nordtal.s2.smp.aura;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a death costs, and the one exemption there is.
 *
 * <p>It is deliberately <em>one</em> rule: everything costs, the arena costs nothing, and the
 * listed causes cost more.
 */
class DeathPenaltyTest {

    private final DeathPenalty penalty = new DeathPenalty(5, 20,
            Set.of("lava", "cactus", "in_wall"));

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
        // The ±10 stake has already settled the fight, so a death penalty on top would make every
        // duel a net loss for both players.
        assertEquals(0, penalty.deltaFor("player_attack", true));
        assertEquals(0, penalty.deltaFor("lava", true), "even a listed cause, inside the arena");
    }

    @Test
    void aNamespaceAndCaseAreBothIgnored() {
        // A rule that stopped matching because somebody typed the namespace would be invisible.
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
        // Each was considered as an exemption and dropped, including the dragon fight, where until
        // the dragon falls dying is the only way home.
        assertEquals(-5, penalty.deltaFor("outside_border", false));
        assertEquals(-5, penalty.deltaFor("out_of_world", false));
        assertEquals(-5, penalty.deltaFor("dragon_breath", false));
    }

    @Test
    void aNegativelyConfiguredPenaltyIsRefused() {
        // The config carries positive numbers and this class subtracts them; a negative would pay
        // a player for dying.
        assertThrows(IllegalArgumentException.class, () -> new DeathPenalty(-5, 20, Set.of()));
    }

    @Test
    void anEmptyListedSetLeavesOneRuleForEverything() {
        final DeathPenalty flat = new DeathPenalty(5, 20, Set.of());

        assertEquals(-5, flat.deltaFor("lava", false));
        assertTrue(flat.listedCauses().isEmpty());
    }
}
