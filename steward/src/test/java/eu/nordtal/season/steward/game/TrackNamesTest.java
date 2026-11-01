package eu.nordtal.season.steward.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TrackNamesTest {

    private static final TrackNames FOOTHOLD = new TrackNames(
            values -> "foothold".equals(values.get("milestone")) ? "Foothold" : "",
            values -> Map.of("milestone", "foothold", "objective", "logs").equals(values) ? "Logs" : "");

    @Test
    void aNamedMilestoneAndObjectiveShowTheirNameAndAnyOtherItsId() {
        assertEquals("Foothold", FOOTHOLD.milestone("foothold"));
        assertEquals("harbour", FOOTHOLD.milestone("harbour"));
        assertEquals("Logs", FOOTHOLD.objective("foothold", "logs"));
        assertEquals("logs", FOOTHOLD.objective("settlement", "logs"));
    }

    @Test
    void theNamesAreReadAgainOnlyOnceTheyAreNoLongerFresh() {
        final AtomicInteger reads = new AtomicInteger();
        final Instant[] now = {Instant.parse("2026-11-01T12:00:00Z")};
        final Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(final java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0];
            }
        };
        final Supplier<TrackNames> names = TrackNames.cached(clock, Duration.ofMinutes(1), () -> {
            reads.incrementAndGet();
            return FOOTHOLD;
        });

        assertEquals("Foothold", names.get().milestone("foothold"));
        now[0] = now[0].plusSeconds(59);
        assertEquals("Foothold", names.get().milestone("foothold"));
        assertEquals(1, reads.get());
        now[0] = now[0].plusSeconds(1);
        assertEquals("Foothold", names.get().milestone("foothold"));
        assertEquals(2, reads.get());
    }

    @Test
    void aFailedReadNamesByIdUntilTheNextRead() {
        final Supplier<TrackNames> names =
                TrackNames.cached(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), Duration.ofMinutes(1), () -> {
                    throw new IllegalStateException("steward-agent is unreachable");
                });

        assertEquals("foothold", names.get().milestone("foothold"));
    }
}
