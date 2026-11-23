package eu.nordtal.season.smp.wall;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.smp.config.CustomBorderSpec;
import eu.nordtal.season.spec.Specs;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class BorderWallTest {

    @Test
    void regionsArrivingBeforeOpenStartsAMovingFrontWhileStartupAndResetSetAtOnce() {
        final AtomicLong now = new AtomicLong(1_000);
        final BorderWall border =
                new BorderWall(clock(now), Runnable::run, ignored -> {}, ignored -> false, "nordtal", () -> 2.0);
        border.apply(twoRegionSpec(), List.of());

        // Startup loads the initial region immediately without a moving front.
        border.regions(Set.of("base"), List.of());
        assertFalse(border.spreading());
        final Wall atStart = assertPresent(border.wall("nordtal"));
        assertTrue(atStart.inside(5, 5));
        assertFalse(atStart.inside(15, 5));

        // Track refresh arrives before ObjectiveEngine's open() callback: must start a moving front.
        border.regions(Set.of("base", "east"), List.of());
        assertTrue(border.spreading());
        assertFalse(assertPresent(border.wall("nordtal")).inside(15, 5));

        // Subsequent open() for the same region is a no-op and does not skip or reset the front.
        now.set(6_000);
        border.open(List.of("east"), List.of());
        border.tick(List.of());
        final Wall halfway = assertPresent(border.wall("nordtal"));
        assertTrue(border.spreading());
        assertTrue(halfway.inside(19.5, 5), "ten blocks reached in five seconds at two blocks per second");
        assertFalse(halfway.inside(21.5, 5), "beyond the front stays closed");

        // Track reset removes regions immediately without a front.
        border.regions(Set.of("base"), List.of());
        assertFalse(border.spreading());
        final Wall afterReset = assertPresent(border.wall("nordtal"));
        assertTrue(afterReset.inside(5, 5));
        assertFalse(afterReset.inside(15, 5));
    }

    private static Wall assertPresent(final @Nullable Wall wall) {
        assertNotNull(wall);
        return wall;
    }

    private static CustomBorderSpec twoRegionSpec() {
        return Specs.createUnsafe(
                CustomBorderSpec.class,
                Map.of(
                        "worlds",
                        List.of(Specs.createUnsafe(
                                CustomBorderSpec.World.class,
                                Map.of(
                                        "world",
                                        "nordtal",
                                        "enabled",
                                        true,
                                        "polygons",
                                        List.of(
                                                polygon("base", 0, 0, 10, 0, 10, 10, 0, 10),
                                                polygon("east", 10, 0, 30, 0, 30, 10, 10, 10)))))));
    }

    private static CustomBorderSpec.Polygon polygon(final String name, final int... coords) {
        final List<CustomBorderSpec.Corner> corners = new java.util.ArrayList<>();
        for (int i = 0; i < coords.length; i += 2) {
            corners.add(Specs.createUnsafe(CustomBorderSpec.Corner.class, Map.of("x", coords[i], "z", coords[i + 1])));
        }
        return Specs.createUnsafe(CustomBorderSpec.Polygon.class, Map.of("name", name, "corners", corners));
    }

    private static Clock clock(final AtomicLong millis) {
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(final ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(millis.get());
            }
        };
    }
}
