package eu.nordtal.season.proxy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A settable {@link Clock}, so tests advance time instead of sleeping through it. */
public final class MutableClock extends Clock {

    private Instant now;

    public MutableClock(final Instant now) {
        this.now = now;
    }

    public void advance(final Duration by) {
        now = now.plus(by);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
        throw new UnsupportedOperationException();
    }
}
