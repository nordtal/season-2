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
        properties.setProperty("heights", "1,26,4");
        properties.setProperty("row-width", "254");
        properties.setProperty("item-width", "1");
        properties.setProperty("plate-item", "27");
        properties.setProperty("lone-item", "2");
        properties.setProperty("plate-x", "40");
        properties.setProperty("item-place", "9");
        properties.setProperty("hover.0", "1,120");
        final WheelTimetable timetable = WheelTimetable.parse(properties);
        assertEquals(199, timetable.cooldown());
        assertEquals(List.of(0, 3, 6), timetable.pops());
        assertEquals(List.of(0, 5), timetable.variants());
        assertEquals(List.of(50, 51), timetable.clicks().get(0));
        assertEquals(List.of(), timetable.clicks().get(5));
        assertEquals(24, timetable.open());
        assertEquals(180, timetable.end(0));
        assertEquals(199, timetable.end(5));
        assertEquals(List.of(1, 26, 4), timetable.rows().heights());
        assertEquals(40, timetable.rows().plateX());
        assertEquals(
                Map.of(0, new WheelTimetable.Hover(1, 120)), timetable.rows().hovers());
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
        assertEquals(11, timetable.rows().hovers().size());
        final WheelTimetable.Rows rows = timetable.rows();
        // A hover's 17 advance stays inside its row's line: the text box but its padding of 4 on each side.
        assertTrue(rows.hovers().values().stream().allMatch(hover -> {
            final int item = hover.body() == 3 ? rows.plateItem() : rows.itemWidth();
            return hover.x() >= 0 && hover.x() + 17 <= rows.rowWidth() - item - 2 - 8;
        }));
    }
}
