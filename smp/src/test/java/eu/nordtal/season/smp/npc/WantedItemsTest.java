package eu.nordtal.season.smp.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.messagerendering.GameDocument;
import eu.nordtal.season.smp.menu.PanelWalk;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The client draws a lore line as one row and never wraps it, so a long item list has to be many lines. */
class WantedItemsTest {

    private static final GameDocument DOCUMENT =
            PanelWalk.document(HandInGui.DOCUMENT, Locale.ENGLISH, new HandInGui.Window(5));

    @Test
    void everyWantedItemGetsALineOfItsOwn() {
        final List<String> lines =
                sample(List.of("nordtal:one", "nordtal:two", "nordtal:three", "nordtal:four", "nordtal:five"));
        assertEquals(
                List.of("nordtal:one", "nordtal:two", "nordtal:three", "nordtal:four", "nordtal:five"),
                lines.subList(lines.size() - 5, lines.size()));
    }

    @Test
    void noItemsGiveNoLines() {
        assertEquals(
                sample(List.of("nordtal:one")).size() - 1, sample(List.of()).size());
    }

    private static List<String> sample(final List<String> wanted) {
        return PanelWalk.lines(DOCUMENT.item("sample", null, Map.of("wanted", HandInGui.wanted(wanted))));
    }
}
