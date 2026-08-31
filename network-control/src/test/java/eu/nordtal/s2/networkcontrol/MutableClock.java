package eu.nordtal.s2.networkcontrol;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A settable {@link Clock}, so the tests in this module advance time instead of sleeping through
 * it. Shared by everything here that is a function of elapsed time - the fallback cache window and
 * the play-time counter - because two copies of it would eventually disagree.
 */
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
