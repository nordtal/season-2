package eu.nordtal.season.papercommon.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.database.npc.Dialogue;
import eu.nordtal.season.database.npc.Dialogue.Step;
import eu.nordtal.season.database.npc.Dialogue.Words;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A skip opens its menu straight away while the dialogue still hands to it, and the hand-off writes the box. */
class SkipChoiceTest {

    private static final Dialogue TO_OBJECTIVES = new Dialogue(
            "hello",
            List.of(
                    new Step("hello", 0, 0, List.of(new Words("Hello.", "Hallo.")), List.of(), "go", null),
                    new Step("go", 0, 0, List.of(new Words("Off you go.", "Los.")), List.of(), null, "objectives")));

    @Test
    void theMenusAreTheOnesTheStepsHandTo() {
        assertEquals(Set.of("objectives"), SkipChoice.menus(TO_OBJECTIVES));
    }

    @Test
    void withoutASkipTheDialoguePlays() {
        assertEquals(SkipChoice.Start.PLAY, SkipChoice.start(null, TO_OBJECTIVES, false));
        assertEquals(SkipChoice.Start.PLAY, SkipChoice.start(null, TO_OBJECTIVES, true));
    }

    @Test
    void aSkipOpensItsMenuStraightAway() {
        assertEquals(SkipChoice.Start.OPEN_MENU, SkipChoice.start("objectives", TO_OBJECTIVES, false));
    }

    @Test
    void shiftOnASkippedNpcReplaysTheDialogue() {
        assertEquals(SkipChoice.Start.REPLAY, SkipChoice.start("objectives", TO_OBJECTIVES, true));
    }

    @Test
    void aStaleSkipPlaysTheDialogue() {
        assertEquals(SkipChoice.Start.PLAY, SkipChoice.start("shop", TO_OBJECTIVES, false));
        assertEquals(SkipChoice.Start.PLAY, SkipChoice.start("shop", TO_OBJECTIVES, true));
    }

    @Test
    void aTickedBoxSkipsUnlessTheSameSkipIsStoredAlready() {
        assertEquals(SkipChoice.Change.SKIP, SkipChoice.handOff("objectives", true, null));
        assertEquals(SkipChoice.Change.NONE, SkipChoice.handOff("objectives", true, "objectives"));
        assertEquals(SkipChoice.Change.SKIP, SkipChoice.handOff("objectives", true, "shop"));
    }

    @Test
    void anUntickedBoxUnskipsOnlyWhereASkipWasStored() {
        assertEquals(SkipChoice.Change.UNSKIP, SkipChoice.handOff("objectives", false, "objectives"));
        assertEquals(SkipChoice.Change.UNSKIP, SkipChoice.handOff("objectives", false, "shop"));
        assertEquals(SkipChoice.Change.NONE, SkipChoice.handOff("objectives", false, null));
    }
}
