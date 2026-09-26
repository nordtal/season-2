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
 * <b>Why this is worth a test at all</b>
 *
 * It is three lines of {@code switch} and it has exactly one way to be wrong: two arms crossed. That is invisible
 * everywhere it matters - the three points are all valid coordinates, the teleport succeeds, {@code LandingSite}
 * finds ground, the player is told they arrived, and the only symptom is somebody standing in the wrong world's
 * landing site wondering what happened. Nothing throws and no log line is written.
 *
 * Crossed arms are also what the defaults cannot catch: the Nether and End placeholders in {@code DefaultSmp} share
 * their x and z, so a config.yml round trip would pass with those two arms crossed in everything but height. Hence
 * the deliberately distinct numbers below rather than the real defaults.
 *
 * <b>No server.</b> {@code Worlds}' constructor reads three strings out of the config and {@code balloonSpawnPoint}
 * reads the config again - neither touches {@code Bukkit}, which is the whole reason this one method can be tested
 * here while the rest of the class cannot.
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

    /**
     * A point whose five numbers are all different from every other point's.
     *
     * An anonymous implementation rather than {@code Specs.createUnsafe}: every method on these interfaces is a
     * {@code default}, so overriding the five that are read is enough, and it keeps this test out of {@code DefaultSmp}
     * 's package-private company.
     */
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
