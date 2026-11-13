package eu.nordtal.season.packrendering.wheel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
        final WheelTimetable timetable = WheelTimetable.parse(properties);
        assertEquals(199, timetable.cooldown());
        assertEquals(List.of(0, 3, 6), timetable.pops());
        assertEquals(List.of(0, 5), timetable.variants());
        assertEquals(List.of(50, 51), timetable.clicks().get(0));
        assertEquals(List.of(), timetable.clicks().get(5));
        assertEquals(24, timetable.open());
        assertEquals(180, timetable.end(0));
        assertEquals(199, timetable.end(5));
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
    }
}
