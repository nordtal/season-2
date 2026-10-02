package eu.nordtal.s2.common.time;

import java.time.Clock;
import java.time.ZoneId;

/** The clock each process creates once at startup and hands to everything that asks. */
public final class NetworkTime {

    private NetworkTime() {}

    /** Returns the system clock in UTC, for a process that only counts instants; the one place it is read. */
    public static Clock clock() {
        return Clock.systemUTC();
    }

    /** Returns the system clock in {@code zone}, the default zone of the network's settings for whatever it shows. */
    public static Clock clock(final ZoneId zone) {
        return Clock.system(zone);
    }
}
