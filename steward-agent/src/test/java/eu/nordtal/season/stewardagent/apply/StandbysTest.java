package eu.nordtal.season.stewardagent.apply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.stewardagent.topology.DeclaredTopology;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a standby's {@code plugins/} holds after a run.
 *
 * A replacement must come up on the same jars and the same data files as the service it replaces.
 */
class StandbysTest {

    @TempDir
    Path volumes;

    @Test
    void theStandbyGetsTheJarsAndTheDataFilesTheLiveServiceHas() throws IOException {
        write("proxy/plugins/proxy-0.9.3.jar", "new");
        write("proxy/plugins/proxy/icon.png", "icon");
        mounted(DeclaredTopology.topology().standbyOf(Topology.PROXY).orElseThrow());

        final List<ApplyResult.Outcome> outcomes =
                Standbys.fill(volumes, List.of(Topology.PROXY), DeclaredTopology.topology());

        assertEquals(1, outcomes.size(), "one row per mounted standby: " + outcomes);
        assertEquals(ApplyResult.Status.DONE, outcomes.getFirst().status(), detail(outcomes));
        assertEquals(
                "proxy-standby",
                outcomes.getFirst().service(),
                "the row is filed under the standby's own compose service name, or a failure here"
                        + " would read as a failure of the proxy update itself");
        assertEquals("new", read("proxy-standby/plugins/proxy-0.9.3.jar"));
        assertEquals(
                "icon",
                read("proxy-standby/plugins/proxy/icon.png"),
                "the standby shows a transferred player's server list a different network");
    }

    @Test
    void aJarTheLiveServiceNoLongerHasIsRemovedFromTheStandby() throws IOException {
        write("limbo/plugins/limbo-0.9.3.jar", "new");
        write("limbo-standby/plugins/limbo-0.9.2.jar", "old");

        Standbys.fill(volumes, List.of(Topology.LIMBO), DeclaredTopology.topology());

        assertTrue(Files.isRegularFile(volumes.resolve("limbo-standby/plugins/limbo-0.9.3.jar")));
        assertFalse(
                Files.exists(volumes.resolve("limbo-standby/plugins/limbo-0.9.2.jar")),
                "the standby still carries the superseded jar, so it would start on two versions of"
                        + " the same plugin - which is a server that does not start at all");
    }

    @Test
    void aPluginsScratchDirectoryIsNeitherCopiedNorRemovedSoTheRunningStandbyKeepsItsOwn() throws IOException {
        // spark, which Paper bundles, writes its profile into plugins/spark/tmp while the standby runs.
        write("limbo/plugins/limbo-0.9.3.jar", "new");
        write("limbo/plugins/spark/config.json", "{}");
        write("limbo/plugins/spark/tmp/live.jfr.tmp", "the live server's");
        write("limbo-standby/plugins/spark/tmp/standby.jfr.tmp", "the standby's");

        final List<ApplyResult.Outcome> first =
                Standbys.fill(volumes, List.of(Topology.LIMBO), DeclaredTopology.topology());

        assertEquals(ApplyResult.Status.DONE, first.getFirst().status(), detail(first));
        assertEquals("{}", read("limbo-standby/plugins/spark/config.json"));
        assertEquals(
                "the standby's",
                read("limbo-standby/plugins/spark/tmp/standby.jfr.tmp"),
                "the fill deleted a file the running standby's profiler was still writing");
        assertFalse(
                Files.exists(volumes.resolve("limbo-standby/plugins/spark/tmp/live.jfr.tmp")),
                "the live server's scratch file was copied into the standby");
        assertEquals(
                ApplyResult.Status.UNCHANGED,
                Standbys.fill(volumes, List.of(Topology.LIMBO), DeclaredTopology.topology())
                        .getFirst()
                        .status(),
                "two scratch directories that differ are no change to mirror");
    }

    @Test
    void aSecondRunInARowCopiesNothingAndSaysSo() throws IOException {
        write("proxy/plugins/proxy-0.9.3.jar", "new");
        mounted(DeclaredTopology.topology().standbyOf(Topology.PROXY).orElseThrow());

        Standbys.fill(volumes, List.of(Topology.PROXY), DeclaredTopology.topology());
        final List<ApplyResult.Outcome> second =
                Standbys.fill(volumes, List.of(Topology.PROXY), DeclaredTopology.topology());

        // A second run right after a real one must come back UNCHANGED; an unconditional mirror would say DONE forever.
        assertEquals(ApplyResult.Status.UNCHANGED, second.getFirst().status(), detail(second));
    }

    @Test
    void aFileThatChangedWithoutChangingSizeIsCopied() throws IOException {
        // A replaced icon or a hash in a file keeps its size, so comparing by size hides a change.
        write("proxy/plugins/proxy/icon.png", "aaaa");
        mounted(DeclaredTopology.topology().standbyOf(Topology.PROXY).orElseThrow());
        Standbys.fill(volumes, List.of(Topology.PROXY), DeclaredTopology.topology());

        write("proxy/plugins/proxy/icon.png", "bbbb");
        final List<ApplyResult.Outcome> second =
                Standbys.fill(volumes, List.of(Topology.PROXY), DeclaredTopology.topology());

        assertEquals(
                "bbbb",
                read("proxy-standby/plugins/proxy/icon.png"),
                "the standby kept the old file because it had the same size");
        assertEquals(ApplyResult.Status.DONE, second.getFirst().status(), detail(second));
    }

    @Test
    void aServiceWithoutAStandbyInComposeYmlGetsNoRowAtAll() throws IOException {
        write("proxy/plugins/proxy-0.9.3.jar", "new");

        // No proxy-standby directory: an older stack without this feature must not grow a skipped line in the report.
        assertEquals(List.of(), Standbys.fill(volumes, List.of(Topology.PROXY), DeclaredTopology.topology()));
    }

    @Test
    void onlyTheServicesTheRunTouchedAreMirrored() throws IOException {
        write("proxy/plugins/proxy-0.9.3.jar", "new");
        write("limbo/plugins/limbo-0.9.3.jar", "new");
        mounted(DeclaredTopology.topology().standbyOf(Topology.PROXY).orElseThrow());
        mounted(DeclaredTopology.topology().standbyOf(Topology.LIMBO).orElseThrow());

        final List<ApplyResult.Outcome> outcomes =
                Standbys.fill(volumes, List.of(Topology.LIMBO), DeclaredTopology.topology());

        assertEquals(
                List.of("limbo-standby"),
                outcomes.stream().map(ApplyResult.Outcome::service).toList());
        assertFalse(
                Files.exists(volumes.resolve("proxy-standby/plugins/proxy-0.9.3.jar")),
                "a run scoped to limbo wrote into the proxy's standby");
    }

    @Test
    void aMountedStandbyWithNothingToCopyFromIsAFailureNotASilence() throws IOException {
        mounted(DeclaredTopology.topology().standbyOf(Topology.PROXY).orElseThrow());

        final List<ApplyResult.Outcome> outcomes =
                Standbys.fill(volumes, List.of(Topology.PROXY), DeclaredTopology.topology());

        assertEquals(ApplyResult.Status.FAILED, outcomes.getFirst().status(), detail(outcomes));
        // The status alone could also mean an unhandled exception; the row must name which directory was empty.
        assertTrue(
                String.valueOf(outcomes.getFirst().detail()).contains("refuse to start"),
                "the failure does not say what an empty standby costs: " + detail(outcomes));
        assertTrue(
                String.valueOf(outcomes.getFirst().detail()).contains("plugins"),
                "the failure does not name the directory it could not read: " + detail(outcomes));
    }

    private void write(final String relative, final String content) throws IOException {
        final Path file = volumes.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** A standby whose volume is mounted here and whose plugins/ is still empty. */
    private void mounted(final String standby) throws IOException {
        Files.createDirectories(volumes.resolve(standby));
    }

    private String read(final String relative) throws IOException {
        return Files.readString(volumes.resolve(relative), StandardCharsets.UTF_8);
    }

    private static String detail(final List<ApplyResult.Outcome> outcomes) {
        return String.valueOf(outcomes);
    }
}
