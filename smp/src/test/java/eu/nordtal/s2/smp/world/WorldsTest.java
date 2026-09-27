package eu.nordtal.s2.smp.world;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.smp.config.BalloonSpawnPointsSpec;
import eu.nordtal.s2.smp.config.SmpSpec;
import eu.nordtal.s2.smp.config.SpawnPointSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * That {@link Worlds#balloonSpawnPoint} hands back the point that was configured for that role.
 *
 * Every number is distinct so two crossed {@code switch} arms cannot pass.
 */
class WorldsTest {

    @Test
    void eachRoleGetsItsOwnPoint() {
        // One point per role, every field distinct, so a crossed arm cannot land on a value that happens to match.
        final Map<WorldRole, SpawnPointSpec> expected = new LinkedHashMap<>();
        int index = 1;
        for (final WorldRole role : WorldRole.values()) {
            expected.put(role, point(index));
            index++;
        }

        final Worlds worlds = new Worlds(configWith(expected));

        final List<Executable> checks = new ArrayList<>();
        for (final WorldRole role : WorldRole.values()) {
            final SpawnPointSpec want = expected.get(role);
            final SpawnPointSpec got = worlds.balloonSpawnPoint(role);
            checks.add(() -> assertEquals(want.x(), got.x(), role + ": x"));
            checks.add(() -> assertEquals(want.y(), got.y(), role + ": y"));
            checks.add(() -> assertEquals(want.z(), got.z(), role + ": z"));
            checks.add(() -> assertEquals(want.yaw(), got.yaw(), role + ": yaw"));
            checks.add(() -> assertEquals(want.pitch(), got.pitch(), role + ": pitch"));
        }
        assertAll(checks);
    }

    /** A point whose five numbers are all different from every other point's. */
    private static SpawnPointSpec point(final int seed) {
        return new SpawnPointSpec() {
            @Override
            public double x() {
                return seed * 100.0;
            }

            @Override
            public double y() {
                return seed * 10.0;
            }

            @Override
            public double z() {
                return seed * 1000.0;
            }

            @Override
            public float yaw() {
                return seed * 7.0f;
            }

            @Override
            public float pitch() {
                return seed * -3.0f;
            }
        };
    }

    private static SmpSpec configWith(final Map<WorldRole, SpawnPointSpec> points) {
        final BalloonSpawnPointsSpec section = new BalloonSpawnPointsSpec() {
            @Override
            public SpawnPointSpec nordtal() {
                return points.get(WorldRole.NORDTAL);
            }

            @Override
            public SpawnPointSpec nether() {
                return points.get(WorldRole.NETHER);
            }

            @Override
            public SpawnPointSpec end() {
                return points.get(WorldRole.END);
            }
        };
        return new SmpSpec() {
            @Override
            public BalloonSpawnPointsSpec balloonSpawnPoints() {
                return section;
            }
        };
    }
}
