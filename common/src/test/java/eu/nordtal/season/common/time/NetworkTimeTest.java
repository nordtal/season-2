package eu.nordtal.season.common.time;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class NetworkTimeTest {

    @Test
    void aClockForInstantsAloneRunsInUtc() {
        assertEquals(ZoneOffset.UTC, NetworkTime.clock().getZone());
    }

    @Test
    void aClockThatShowsTimesRunsInTheZoneItIsGiven() {
        final ZoneId zone = ZoneId.of("America/Chicago");
        assertEquals(zone, NetworkTime.clock(zone).getZone());
    }
}
