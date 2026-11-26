package eu.nordtal.season.smp.wheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.packrendering.Glyphs;
import eu.nordtal.season.packrendering.wheel.WheelTimetable;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import org.junit.jupiter.api.Test;

class WheelDialogTest {

    private static final WheelTimetable.Rows ROWS = WheelTimetable.load().rows();
    private static final WheelTimetable.Rows NO_HOVERS = new WheelTimetable.Rows(
            ROWS.heights(),
            ROWS.lines(),
            ROWS.rowWidth(),
            ROWS.itemWidth(),
            ROWS.loneItem(),
            ROWS.plateX(),
            ROWS.plateY(),
            java.util.Map.of());
    private static final WheelDialog.Texts TEXTS = new WheelDialog.Texts(
            Component.text("Wheel of Fortune"),
            "Wheel of Fortune",
            "3 spins left",
            "Spin",
            "Spin",
            "Costs one spin.",
            "No spins left",
            "Close");

    @Test
    void descriptionHasTwelveLinesWithFocusCoverOnLineZero() {
        final Component desc = WheelDialog.description(TEXTS, false, null, ROWS, null);
        final List<Component> lines = splitLines(desc);
        assertEquals(12, ROWS.lines());
        assertEquals(ROWS.lines(), lines.size());
        final String line0 = plainGlyphs(lines.getFirst());
        assertTrue(line0.contains(Glyphs.UI_WHEEL_RING_LEFT));
        assertTrue(line0.contains(Glyphs.UI_WHEEL_RING_RIGHT));
    }

    @Test
    void turningDescriptionDisablesSpinButtonEvenWhenSpinsRemain() {
        final ClickEvent click = ClickEvent.suggestCommand("/spin");
        final Component desc = WheelDialog.description(TEXTS, true, click, ROWS, null);
        final List<Component> lines = splitLines(desc);
        assertFalse(hasClickEvent(desc));
        assertFalse(hasHoverEvent(lines.get(5)));
        assertFalse(hasHoverEvent(lines.get(6)));
    }

    @Test
    void restingDescriptionWithSpinsEnablesMultiLinePlateClickAndHover() {
        final ClickEvent click = ClickEvent.suggestCommand("/spin");
        final Component desc = WheelDialog.description(TEXTS, true, click, NO_HOVERS, List.of());
        final List<Component> lines = splitLines(desc);
        assertTrue(hasClickEvent(lines.get(5)));
        assertTrue(hasClickEvent(lines.get(6)));
        assertTrue(hasHoverEvent(lines.get(5)));
        assertTrue(hasHoverEvent(lines.get(6)));
        assertFalse(hasClickEvent(lines.get(7)));
    }

    @Test
    void restingDescriptionWithoutSpinsShowsDisabledHoverWithoutClick() {
        final Component desc = WheelDialog.description(TEXTS, false, null, NO_HOVERS, List.of());
        final List<Component> lines = splitLines(desc);
        assertFalse(hasClickEvent(desc));
        assertTrue(hasHoverEvent(lines.get(5)));
        assertTrue(hasHoverEvent(lines.get(6)));
    }

    private static List<Component> splitLines(final Component root) {
        final List<Component> lines = new ArrayList<>();
        for (final Component child : root.children()) {
            if (!Component.newline().equals(child)) {
                lines.add(child);
            }
        }
        return lines;
    }

    private static String plainGlyphs(final Component component) {
        final StringBuilder out = new StringBuilder();
        collectText(component, out);
        return out.toString();
    }

    private static void collectText(final Component component, final StringBuilder out) {
        if (component instanceof final TextComponent text) {
            out.append(text.content());
        }
        component.children().forEach(child -> collectText(child, out));
    }

    private static boolean hasClickEvent(final Component component) {
        if (component.clickEvent() != null) {
            return true;
        }
        return component.children().stream().anyMatch(WheelDialogTest::hasClickEvent);
    }

    private static boolean hasHoverEvent(final Component component) {
        if (component.hoverEvent() != null) {
            return true;
        }
        return component.children().stream().anyMatch(WheelDialogTest::hasHoverEvent);
    }
}
