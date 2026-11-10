package eu.nordtal.season.smp.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.packrendering.ui.Symbol;
import eu.nordtal.season.smp.milestone.Objective;
import eu.nordtal.season.smp.milestone.ObjectiveType;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObjectiveDialogTest {

    private static Objective objective(final ObjectiveType type, final String statistic) {
        return new Objective("key", type, "role", 10, List.of("IRON_INGOT"), statistic, List.of(), "story/root", 0, 0);
    }

    @Test
    void eachKindOfObjectiveShowsItsOwnSymbol() {
        assertEquals(Symbol.HANDIN, ObjectiveDialog.symbol(objective(ObjectiveType.HAND_IN, "")));
        assertEquals(Symbol.FLAG, ObjectiveDialog.symbol(objective(ObjectiveType.ADVANCEMENT, "")));
        assertEquals(Symbol.SWORDS, ObjectiveDialog.symbol(objective(ObjectiveType.STATISTIC, "KILL_ENTITY")));
        assertEquals(Symbol.PICKAXE, ObjectiveDialog.symbol(objective(ObjectiveType.STATISTIC, "MINE_BLOCK")));
    }
}
