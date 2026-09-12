package eu.nordtal.s2.smp.world;

import eu.nordtal.s2.smp.config.SmpSpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * That {@link Worlds#balloonSpawnPoint} hands back the point that was configured for that role.
 *
 * <h2>Why this is worth a test at all</h2>
 * It is four lines of {@code switch} and it has exactly one way to be wrong: two arms crossed. That
 * is invisible everywhere it matters - the four points are all valid coordinates, the teleport
 * succeeds, {@code LandingSite} finds ground, the player is told they arrived, and the only symptom
 * is somebody standing in the wrong world's landing site wondering what happened. Nothing throws
 * and no log line is written.
 *
 * <p>Crossed arms are also what the defaults cannot catch: two of the four placeholder points in
 * {@code DefaultSmp} are the same coordinates, so a config.yml round trip would pass with FARM and
 * END swapped. Hence the deliberately distinct numbers below rather than the real defaults.
 *
 * <p><b>No server.</b> {@code Worlds}' constructor reads four strings out of the config and
 * {@code balloonSpawnPoint} reads the config again - neither touches {@code Bukkit}, which is the
 * whole reason this one method can be tested here while the rest of the class cannot.
 */
class WorldsTest {

    @Test
    @DisplayName("each role gets its own configured landing point, not a neighbour's")
    void eachRoleGetsItsOwnPoint() {
        // One point per role, every field distinct, so a crossed arm cannot land on a value that
        // happens to match. The x is the role's ordinal and everything else is derived from it.
        final Map<WorldRole, SmpSpec.SpawnPointSpec> expected = new LinkedHashMap<>();
        for (final WorldRole role : WorldRole.values()) {
            expected.put(role, point(role.ordinal() + 1));
        }

        final Worlds worlds = new Worlds(configWith(expected));

        final List<Executable> checks = new ArrayList<>();
        for (final WorldRole role : WorldRole.values()) {
            final SmpSpec.SpawnPointSpec want = expected.get(role);
            final SmpSpec.SpawnPointSpec got = worlds.balloonSpawnPoint(role);
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
     * <p>An anonymous implementation rather than {@code Specs.createUnsafe}: every method on these
     * interfaces is a {@code default}, so overriding the five that are read is enough, and it keeps
     * this test out of {@code DefaultSmp}'s package-private company.
     */
    private static SmpSpec.SpawnPointSpec point(final int seed) {
        return new SmpSpec.SpawnPointSpec() {
            @Override public double x() { return seed * 100.0; }

            @Override public double y() { return seed * 10.0; }

            @Override public double z() { return seed * 1000.0; }

            @Override public float yaw() { return seed * 7.0f; }

            @Override public float pitch() { return seed * -3.0f; }
        };
    }

    private static SmpSpec configWith(final Map<WorldRole, SmpSpec.SpawnPointSpec> points) {
        final SmpSpec.BalloonSpawnPointsSpec section = new SmpSpec.BalloonSpawnPointsSpec() {
            @Override public SmpSpec.SpawnPointSpec nordtal() { return points.get(WorldRole.NORDTAL); }

            @Override public SmpSpec.SpawnPointSpec farm() { return points.get(WorldRole.FARM); }

            @Override public SmpSpec.SpawnPointSpec nether() { return points.get(WorldRole.NETHER); }

            @Override public SmpSpec.SpawnPointSpec end() { return points.get(WorldRole.END); }
        };
        return new SmpSpec() {
            @Override public SmpSpec.BalloonSpawnPointsSpec balloonSpawnPoints() { return section; }
        };
    }
}
