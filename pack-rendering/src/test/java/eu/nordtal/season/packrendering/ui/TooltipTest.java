package eu.nordtal.season.packrendering.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class TooltipTest {

    @Test
    void aLongTextStillOpens() {
        final Component tooltip = assertDoesNotThrow(() -> Tooltip.hover("Title", "word ".repeat(100), null));
        assertFalse(tooltip.toString().contains("……"));
    }

    @Test
    void aFullLastRowGetsOneEllipsis() {
        final List<Tooltip.Row> rows = new ArrayList<>();
        for (int index = 0; index < Tooltip.MAX_ROWS + 1; index++) {
            rows.add(new Tooltip.Row("row " + index, Colours.TOOLTIP_TEXT));
        }
        rows.set(Tooltip.MAX_ROWS - 1, new Tooltip.Row("x".repeat(Tooltip.WRAP), Colours.TOOLTIP_TEXT));
        assertFalse(Tooltip.hover(rows, 1).toString().contains("……"));
    }
}
