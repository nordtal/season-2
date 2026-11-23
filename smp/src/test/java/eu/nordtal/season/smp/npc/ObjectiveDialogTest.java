package eu.nordtal.season.smp.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.packrendering.ui.ItemPicture;
import eu.nordtal.season.packrendering.ui.Symbol;
import eu.nordtal.season.smp.milestone.Objective;
import eu.nordtal.season.smp.milestone.ObjectiveType;
import java.util.List;
import java.util.Locale;
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

    @Test
    void countsFromOneThousandUpAreShortenedAndNeverRoundedUp() {
        assertEquals("0", ObjectiveDialog.shortCount(0, Locale.ENGLISH));
        assertEquals("999", ObjectiveDialog.shortCount(999, Locale.GERMAN));
        assertEquals("1k", ObjectiveDialog.shortCount(1000, Locale.ENGLISH));
        assertEquals("5.2k", ObjectiveDialog.shortCount(5210, Locale.ENGLISH));
        assertEquals("5,2k", ObjectiveDialog.shortCount(5210, Locale.GERMAN));
        assertEquals("8.9k", ObjectiveDialog.shortCount(8999, Locale.ENGLISH));
        assertEquals("8,9k", ObjectiveDialog.shortCount(8999, Locale.GERMAN));
        assertEquals("9k", ObjectiveDialog.shortCount(9000, Locale.ENGLISH));
    }

    @Test
    void objectiveIconDerivesFromMaterialSubjectOrAdvancementWithSymbolFallback() {
        final Objective handIn = new Objective(
                "iron", ObjectiveType.HAND_IN, "", 64, List.of("IRON_INGOT", "IRON_BLOCK"), "", List.of(), "", 0, 0);
        final ItemPicture handInIcon = ObjectiveDialog.icon(handIn, false, key -> null);
        assertTrue(handInIcon.hasSprite());
        assertEquals(Symbol.HANDIN, handInIcon.fallback());

        final Objective statWithBlock = new Objective(
                "mine", ObjectiveType.STATISTIC, "", 500, List.of(), "MINE_BLOCK", List.of("DIAMOND_ORE"), "", 0, 0);
        final ItemPicture statIcon = ObjectiveDialog.icon(statWithBlock, false, key -> null);
        assertTrue(statIcon.hasSprite());
        assertEquals(Symbol.PICKAXE, statIcon.fallback());

        final Objective killWithoutSprite = new Objective(
                "kill", ObjectiveType.STATISTIC, "", 50, List.of(), "KILL_ENTITY", List.of("ZOMBIE"), "", 0, 0);
        final ItemPicture killIcon = ObjectiveDialog.icon(killWithoutSprite, false, key -> null);
        assertFalse(killIcon.hasSprite());
        assertEquals(Symbol.SWORDS, killIcon.fallback());

        final Objective adv = new Objective(
                "end",
                ObjectiveType.ADVANCEMENT,
                "",
                10,
                List.of(),
                "",
                List.of(),
                "minecraft:story/enter_the_end",
                0,
                0);
        final ItemPicture advWithIcon = ObjectiveDialog.icon(adv, false, key -> "minecraft:ender_pearl");
        assertTrue(advWithIcon.hasSprite());
        assertEquals(Symbol.FLAG, advWithIcon.fallback());

        final ItemPicture advFallback = ObjectiveDialog.icon(adv, true, key -> null);
        assertFalse(advFallback.hasSprite());
        assertEquals(Symbol.FLAG, advFallback.fallback());
    }
}
