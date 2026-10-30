package eu.nordtal.season.smp.duel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.menu.MenuTitle;
import eu.nordtal.season.packrendering.menu.SlotGeometry;
import eu.nordtal.season.smp.menu.PanelWalk;
import eu.nordtal.season.smp.menu.PanelWalk.Run;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class NegotiationPanelTest {

    private static final List<NegotiationPanel.Row> KITS =
            List.of(new NegotiationPanel.Row("Sword", "Opponent", true), new NegotiationPanel.Row("Bow", null, false));

    private static Component surface(final String bar) {
        return PanelWalk.surface(NegotiationPanel.title(Component.text("Duel"), KITS, "Starting", bar));
    }

    @Test
    void theReadableTitleStillLandsWhereItWould() {
        final List<Run> runs = PanelWalk.runs(surface(NegotiationPanel.bar(2, 6)));

        assertEquals(MenuTitle.ANCHOR_X, runs.get(runs.size() - 1).end());
    }

    @Test
    void theOwnPickIsFramedAndTheOtherIsNot() {
        final List<Run> runs = PanelWalk.runs(surface(""));

        assertEquals(
                1,
                runs.stream()
                        .filter(run -> run.content().equals(Glyphs.GUI_ROW_FRAME))
                        .count());
        assertEquals(
                SlotGeometry.ORIGIN_X + 2,
                PanelWalk.find(runs, Glyphs.FONT_GUI_ROWS.get(0), Glyphs.GUI_ROW_FRAME)
                        .x(),
                "the frame lies on the first row's pill, the viewer's own pick");
    }

    @Test
    void theCountdownBarIsDrawnFromTheRowFont() {
        final List<String> missing = new ArrayList<>();
        for (final Run run : PanelWalk.runs(surface(NegotiationPanel.bar(3, 6)))) {
            final Set<Integer> declared = PanelWalk.declared(run.font());
            run.whole().codePoints().forEach(codePoint -> {
                if (!declared.contains(codePoint)) {
                    missing.add("U+%X in %s".formatted(codePoint, run.font()));
                }
            });
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void aBarFillsAsManyCellsAsAreDone() {
        assertEquals("██░░░░", NegotiationPanel.bar(2, 6));
        assertEquals("░░░", NegotiationPanel.bar(-1, 3));
        assertEquals("███", NegotiationPanel.bar(4, 3));
    }
}
