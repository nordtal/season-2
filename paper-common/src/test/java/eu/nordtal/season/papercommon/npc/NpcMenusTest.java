package eu.nordtal.season.papercommon.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A menu is registered once, and its hint word comes in the reader's language. */
class NpcMenusTest {

    private static NpcMenus objectives() {
        return new NpcMenus()
                .register(
                        "objectives",
                        locale -> locale.getLanguage().equals("de") ? "Ziele öffnen" : "Open objectives",
                        player -> {});
    }

    @Test
    void theHintComesInTheReadersLanguage() {
        final NpcMenus menus = objectives();
        assertEquals(Map.of("objectives", "Ziele öffnen"), menus.hints(Locale.GERMAN));
        assertEquals(Map.of("objectives", "Open objectives"), menus.hints(Locale.ENGLISH));
    }

    @Test
    void anUnknownMenuIsNone() {
        assertTrue(objectives().menu("shop").isEmpty());
    }

    @Test
    void aMenuIsRegisteredOnce() {
        assertThrows(IllegalArgumentException.class, () -> objectives().register("objectives", locale -> "", p -> {}));
    }

    @Test
    void aMenuWithoutAHintShowsTheNextWord() {
        final Labels labels = new Labels("Next", "Choose", "Answer", "Don't show again", Map.of());
        assertEquals("Next", labels.menu("objectives"));
    }
}
