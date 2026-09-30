package eu.nordtal.s2.common.time;

import java.time.Clock;
import java.time.ZoneId;

/** The network's time zone, and the clock each process creates once at startup and hands to everything that asks. */
public final class NetworkTime {

    /** The zone every date a person types or reads is in, and the one every container runs in. */
    public static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private NetworkTime() {}

    /** Returns the system clock in {@link #ZONE}; the one place a process reads the wall clock from. */
    public static Clock clock() {
        return Clock.system(ZONE);
    }
}
