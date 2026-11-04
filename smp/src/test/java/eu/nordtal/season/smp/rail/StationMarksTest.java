package eu.nordtal.season.smp.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StationMarksTest {

    @Test
    void aMarkReadsBackAsThePlaceItWasMadeFor() {
        assertEquals(new RailPos(37, 70, 21), StationMarks.unpacked(StationMarks.packed(37, 70, 21), 2, 1));
    }

    @Test
    void aMarkBelowZeroInANegativeChunkReadsBackToo() {
        assertEquals(new RailPos(-1, -60, -17), StationMarks.unpacked(StationMarks.packed(-1, -60, -17), -1, -2));
    }
}
