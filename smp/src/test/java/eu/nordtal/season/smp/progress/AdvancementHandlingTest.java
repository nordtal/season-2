package eu.nordtal.season.smp.progress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.papercommon.chat.SystemLines;
import eu.nordtal.season.smp.progress.AdvancementListener.Handling;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.junit.jupiter.api.Test;

class AdvancementHandlingTest {

    @Test
    void anAdvancementOutsideADuelIsPaidAndCredited() {
        assertEquals(Handling.PAY, AdvancementListener.handlingOf("minecraft:story/obtain_armor", false));
    }

    @Test
    void anAdvancementEarnedInADuelIsSilencedAndPaysNothing() {
        assertEquals(Handling.SILENCE, AdvancementListener.handlingOf("minecraft:story/obtain_armor", true));
    }

    @Test
    void aRecipeIsIgnoredInAndOutsideADuel() {
        assertEquals(Handling.IGNORE, AdvancementListener.handlingOf("minecraft:recipes/misc/iron_ingot", false));
        assertEquals(Handling.IGNORE, AdvancementListener.handlingOf("minecraft:recipes/misc/iron_ingot", true));
    }

    @Test
    void aDuelIsSilencedBeforeTheChatAnnouncesTheAdvancement() throws NoSuchMethodException {
        final int silence = priorityOf(AdvancementListener.class);
        final int announce = priorityOf(SystemLines.class);

        assertTrue(silence < announce, "the chat announces only a message the duel has not removed yet");
    }

    private static int priorityOf(final Class<?> listener) throws NoSuchMethodException {
        return listener.getMethod("onAdvancement", PlayerAdvancementDoneEvent.class)
                .getAnnotation(EventHandler.class)
                .priority()
                .getSlot();
    }
}
