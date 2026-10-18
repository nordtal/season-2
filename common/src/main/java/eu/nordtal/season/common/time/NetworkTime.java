package eu.nordtal.season.common.time;

import java.time.Clock;
import java.time.ZoneId;

/** The clock of a process, created once at startup. */
public final class NetworkTime {

    private NetworkTime() {}

    public static Clock clock() {
        return Clock.systemUTC();
    }

    public static Clock clock(final ZoneId zone) {
        return Clock.system(zone);
    }
}
