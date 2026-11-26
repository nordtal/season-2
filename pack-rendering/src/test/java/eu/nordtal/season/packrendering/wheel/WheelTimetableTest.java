package eu.nordtal.season.packrendering.wheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class WheelTimetableTest {

    @Test
    void readsTheVariantsWithDirectAndReboundClicks() {
        final Properties properties = new Properties();
        properties.setProperty("cooldown", "210");
        properties.setProperty("open", "24");
        properties.setProperty("end.0", "170");
        properties.setProperty("end.0.rebound", "205");
        properties.setProperty("pops", "0, 3,6");
        properties.setProperty("variants", "0,5");
        properties.setProperty("clicks.0", "50,51");
        properties.setProperty("clicks.0.rebound", "50,52");
        properties.setProperty("clicks.5", "");
        properties.setProperty("clicks.5.rebound", "60");
        properties.setProperty("heights", "1,116");
        properties.setProperty("lines", "12");
        properties.setProperty("row-width", "216");
        properties.setProperty("item-width", "2");
        properties.setProperty("lone-item", "2");
        properties.setProperty("plate-x", "124");
        properties.setProperty("plate-y", "45");
        properties.setProperty("hover.0", "48,6");
        final WheelTimetable timetable = WheelTimetable.parse(properties);
        assertEquals(210, timetable.cooldown());
        assertEquals(List.of(0, 3, 6), timetable.pops());
        assertEquals(List.of(0, 5), timetable.variants());
        assertEquals(List.of(50, 51), timetable.clicks(0, false));
        assertEquals(List.of(50, 52), timetable.clicks(0, true));
        assertEquals(List.of(), timetable.clicks(5, false));
        assertEquals(List.of(60), timetable.clicks(5, true));
        assertEquals(24, timetable.open());
        assertEquals(170, timetable.end(0, false));
        assertEquals(205, timetable.end(0, true));
        assertEquals(210, timetable.end(5, false));
        assertEquals(210, timetable.end(5, true));
        assertEquals(List.of(1, 116), timetable.rows().heights());
        assertEquals(12, timetable.rows().lines());
        assertEquals(124, timetable.rows().plateX());
        assertEquals(45, timetable.rows().plateY());
        assertEquals(
                Map.of(0, new WheelTimetable.Hover(48, 6)), timetable.rows().hovers());
    }

    @Test
    void aMissingKeyIsRefused() {
        assertThrows(IllegalStateException.class, () -> WheelTimetable.parse(new Properties()));
    }

    /** The build writes the timetable beside the pack it plays, with twelve variants and both endings. */
    @Test
    void theShippedTimetableHoldsTwelveVariantsWithDirectAndReboundEndings() {
        final WheelTimetable timetable = WheelTimetable.load();
        assertEquals(12, timetable.variants().size(), timetable.variants().toString());
        assertEquals(12, timetable.pops().size());
        for (final int variant : timetable.variants()) {
            final int directEnd = timetable.end(variant, false);
            final int reboundEnd = timetable.end(variant, true);
            assertTrue(directEnd > 0 && directEnd < reboundEnd, "variant " + variant);
            assertTrue(reboundEnd <= timetable.cooldown(), "variant " + variant);
            timetable
                    .clicks(variant, false)
                    .forEach(tick -> assertTrue(tick > 0 && tick < directEnd, "direct click at " + tick));
            timetable
                    .clicks(variant, true)
                    .forEach(tick -> assertTrue(tick > 0 && tick < reboundEnd, "rebound click at " + tick));
            assertEquals(
                    timetable.clicks(variant, false).size(),
                    timetable.clicks(variant, true).size(),
                    "rebound suppresses far-pin contact click for variant " + variant);
        }
        final WheelTimetable.Rows rows = timetable.rows();
        assertEquals(List.of(1, 116), rows.heights());
        assertEquals(12, rows.lines());
        assertEquals(12, rows.hovers().size());
        final int lineWidth = rows.rowWidth() - rows.itemWidth() - 2 - 8;
        final int textHeight = rows.lines() * 9;
        assertTrue(rows.hovers().values().stream()
                .allMatch(hover -> hover.x() >= 0
                        && hover.x() + 17 <= lineWidth
                        && hover.y() >= 0
                        && hover.y() + 16 <= textHeight));
    }
}
