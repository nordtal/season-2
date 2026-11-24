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
    void readsTheVariantsWithTheirClicks() {
        final Properties properties = new Properties();
        properties.setProperty("cooldown", "199");
        properties.setProperty("open", "24");
        properties.setProperty("end.0", "180");
        properties.setProperty("pops", "0, 3,6");
        properties.setProperty("variants", "0,5");
        properties.setProperty("clicks.0", "50,51");
        properties.setProperty("clicks.5", "");
        properties.setProperty("heights", "1,161");
        properties.setProperty("lines", "17");
        properties.setProperty("row-width", "254");
        properties.setProperty("item-width", "1");
        properties.setProperty("lone-item", "2");
        properties.setProperty("plate-x", "167");
        properties.setProperty("plate-y", "71");
        properties.setProperty("hover.0", "68,13");
        final WheelTimetable timetable = WheelTimetable.parse(properties);
        assertEquals(199, timetable.cooldown());
        assertEquals(List.of(0, 3, 6), timetable.pops());
        assertEquals(List.of(0, 5), timetable.variants());
        assertEquals(List.of(50, 51), timetable.clicks().get(0));
        assertEquals(List.of(), timetable.clicks().get(5));
        assertEquals(24, timetable.open());
        assertEquals(180, timetable.end(0));
        assertEquals(199, timetable.end(5));
        assertEquals(List.of(1, 161), timetable.rows().heights());
        assertEquals(17, timetable.rows().lines());
        assertEquals(167, timetable.rows().plateX());
        assertEquals(71, timetable.rows().plateY());
        assertEquals(
                Map.of(0, new WheelTimetable.Hover(68, 13)), timetable.rows().hovers());
    }

    @Test
    void aMissingKeyIsRefused() {
        assertThrows(IllegalStateException.class, () -> WheelTimetable.parse(new Properties()));
    }

    /** The build writes the timetable beside the pack it plays, with at least four variants. */
    @Test
    void theShippedTimetableHoldsAtLeastFourVariants() {
        final WheelTimetable timetable = WheelTimetable.load();
        assertTrue(timetable.variants().size() >= 4, timetable.variants().toString());
        assertEquals(12, timetable.pops().size());
        timetable
                .clicks()
                .values()
                .forEach(clicks -> clicks.forEach(
                        tick -> assertTrue(tick > 0 && tick < timetable.cooldown(), "click at " + tick)));
        timetable.variants().forEach(variant -> assertTrue(timetable.end(variant) <= timetable.cooldown()));
        final WheelTimetable.Rows rows = timetable.rows();
        assertEquals(List.of(1, 161), rows.heights());
        assertEquals(17, rows.lines());
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
