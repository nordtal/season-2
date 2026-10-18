package eu.nordtal.season.smp.grave;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.smp.menu.PanelWalk;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class GraveDiedAtLoreTest {

    private static final Instant AT = Instant.parse("2026-09-17T10:00:00Z");

    @Test
    void english() {
        assertEquals(List.of("Somebody", "Died Sep 17, 2026 at 100 64 -200"), owner(Locale.ENGLISH));
    }

    @Test
    void german() {
        assertEquals(List.of("Jemand", "Gestorben am 17.09.2026 bei 100 64 -200"), owner(Locale.GERMAN));
    }

    private static List<String> owner(final Locale locale) {
        return PanelWalk.lines(PanelWalk.document(GraveWindow.DOCUMENT, locale, new GraveWindow.Window(0))
                .item("owner", new GraveWindow.Owner(false, null, AT, 100, 64, -200)));
    }
}
