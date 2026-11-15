package eu.nordtal.season.smp.duel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.junit.jupiter.api.Test;

class DuelAdvancementsTest {

    @Test
    void aFighterIsGrantedNoCriterion() {
        assertTrue(Duels.refusesAdvancements(true, false));
    }

    @Test
    void aFighterWhoDiedIsGrantedNoCriterionUntilTheRespawn() {
        assertTrue(Duels.refusesAdvancements(false, true));
    }

    @Test
    void aPlayerOutsideADuelEarnsAdvancementsAsUsual() {
        assertFalse(Duels.refusesAdvancements(false, false));
    }

    @Test
    void theDuelListenerRefusesTheGrantItselfBeforeAnyOtherListener() throws NoSuchMethodException {
        final EventHandler handler = DuelListener.class
                .getMethod("onCriterion", PlayerAdvancementCriterionGrantEvent.class)
                .getAnnotation(EventHandler.class);

        assertEquals(EventPriority.LOWEST, handler.priority());
    }
}
