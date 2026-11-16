package eu.nordtal.season.papercommon.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.event.block.Action;
import org.junit.jupiter.api.Test;

/** A click on air or on a block counts the same; stepping on a plate counts not. */
class ClickTest {

    @Test
    void eachActionMapsToItsClick() {
        assertEquals(Click.RIGHT, Click.of(Action.RIGHT_CLICK_AIR));
        assertEquals(Click.RIGHT, Click.of(Action.RIGHT_CLICK_BLOCK));
        assertEquals(Click.LEFT, Click.of(Action.LEFT_CLICK_AIR));
        assertEquals(Click.LEFT, Click.of(Action.LEFT_CLICK_BLOCK));
        assertEquals(Click.NONE, Click.of(Action.PHYSICAL));
    }
}
