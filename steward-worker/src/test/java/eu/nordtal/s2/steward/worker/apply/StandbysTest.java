package eu.nordtal.s2.steward.worker.apply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.steward.worker.plan.Topology;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a standby's {@code plugins/} holds after a run (season-2-ops/119).
 *
 * <p>The thing being protected is not "files were copied" - it is that a replacement instance comes
 * up on the same jars and the same {@code pack.yml} as the service it replaces. Every case here is
 * one way that can stop being true without anything saying so.</p>
 */
class StandbysTest {

    @TempDir
    Path volumes;

    @Test
    @DisplayName("the standby gets the jars and the pack.yml the live service just got")
    void theStandbyIsACopy() throws IOException {
        write("proxy/plugins/proxy-0.9.3.jar", "new");
        write("proxy/plugins/proxy/pack.yml", "url: https://example.invalid/pack.zip\nsha1: abc\n");
        mounted(Topology.standbyOf(Topology.PROXY));

        final List<ApplyResult.Outcome> outcomes = Standbys.fill(volumes, List.of(Topology.PROXY));

        assertEquals(1, outcomes.size(), "one row per mounted standby: " + outcomes);
        assertEquals(ApplyResult.Status.DONE, outcomes.getFirst().status(), detail(outcomes));
        assertEquals(
                "proxy-standby",
                outcomes.getFirst().service(),
                "the row is filed under the standby's own compose service name, or a failure here"
                        + " would read as a failure of the proxy update itself");
        assertEquals("new", read("proxy-standby/plugins/proxy-0.9.3.jar"));
        assertEquals(
                "url: https://example.invalid/pack.zip\nsha1: abc\n",
                read("proxy-standby/plugins/proxy/pack.yml"),
                "the standby hands a transferred player a different resource pack to download");
    }

    @Test
    @DisplayName("a jar the live service no longer has is removed from the standby")
    void theOldJarDoesNotSurvive() throws IOException {
        write("limbo/plugins/limbo-0.9.3.jar", "new");
        write("limbo-standby/plugins/limbo-0.9.2.jar", "old");

        Standbys.fill(volumes, List.of(Topology.LIMBO));

        assertTrue(Files.isRegularFile(volumes.resolve("limbo-standby/plugins/limbo-0.9.3.jar")));
        assertFalse(
                Files.exists(volumes.resolve("limbo-standby/plugins/limbo-0.9.2.jar")),
                "the standby still carries the superseded jar, so it would start on two versions of"
                        + " the same plugin - which is a server that does not start at all");
    }

    @Test
    @DisplayName("a second run in a row copies nothing and says so")
    void theSecondRunIsQuiet() throws IOException {
        write("proxy/plugins/proxy-0.9.3.jar", "new");
        mounted(Topology.standbyOf(Topology.PROXY));

        Standbys.fill(volumes, List.of(Topology.PROXY));
        final List<ApplyResult.Outcome> second = Standbys.fill(volumes, List.of(Topology.PROXY));

        // The rule this keeps is the deployment's, not this class's: a second run immediately
        // after a real one has to come back with every line UNCHANGED. A mirror that copied
        // unconditionally would report DONE for ever, and an update run that changed nothing would
        // say it had.
        assertEquals(ApplyResult.Status.UNCHANGED, second.getFirst().status(), detail(second));
    }

    @Test
    @DisplayName("a file that changed without changing size is copied")
    void aSameSizeChangeIsNotMissed() throws IOException {
        // pack.yml is exactly that file and it is the one that matters: a sha1 is forty hex
        // characters whatever the pack is, so a comparison by size would hand every transferred
        // player the previous pack's hash - and the client would refuse a download it cannot
        // verify. The url beside it is usually the same length too, release to release.
        write("proxy/plugins/proxy/pack.yml", "url: https://example.invalid/p.zip\nsha1: aaaa\n");
        mounted(Topology.standbyOf(Topology.PROXY));
        Standbys.fill(volumes, List.of(Topology.PROXY));

        write("proxy/plugins/proxy/pack.yml", "url: https://example.invalid/p.zip\nsha1: bbbb\n");
        final List<ApplyResult.Outcome> second = Standbys.fill(volumes, List.of(Topology.PROXY));

        assertEquals(
                "url: https://example.invalid/p.zip\nsha1: bbbb\n",
                read("proxy-standby/plugins/proxy/pack.yml"),
                "the standby kept the old pack.yml, so a transferred player is told to download a"
                        + " pack under a hash that no longer matches it");
        assertEquals(ApplyResult.Status.DONE, second.getFirst().status(), detail(second));
    }

    @Test
    @DisplayName("a service without a standby in compose.yml gets no row at all")
    void nothingIsWrittenForADeploymentWithoutStandbys() throws IOException {
        write("proxy/plugins/proxy-0.9.3.jar", "new");

        // No proxy-standby directory: the volume is not mounted into this container, which is what
        // a stack from before this feature looks like. It must not grow a skipped line in every
        // report - the case it could hide is caught by the entrypoint, loudly, at the one moment a
        // standby is actually started.
        assertEquals(List.of(), Standbys.fill(volumes, List.of(Topology.PROXY)));
    }

    @Test
    @DisplayName("only the services the run touched are mirrored")
    void anUntouchedServiceIsLeftAlone() throws IOException {
        write("proxy/plugins/proxy-0.9.3.jar", "new");
        write("limbo/plugins/limbo-0.9.3.jar", "new");
        mounted(Topology.standbyOf(Topology.PROXY));
        mounted(Topology.standbyOf(Topology.LIMBO));

        final List<ApplyResult.Outcome> outcomes = Standbys.fill(volumes, List.of(Topology.LIMBO));

        assertEquals(
                List.of("limbo-standby"),
                outcomes.stream().map(ApplyResult.Outcome::service).toList());
        assertFalse(
                Files.exists(volumes.resolve("proxy-standby/plugins/proxy-0.9.3.jar")),
                "a run scoped to limbo wrote into the proxy's standby");
    }

    @Test
    @DisplayName("a mounted standby with nothing to copy from is a failure, not a silence")
    void anEmptySourceIsReported() throws IOException {
        mounted(Topology.standbyOf(Topology.PROXY));

        final List<ApplyResult.Outcome> outcomes = Standbys.fill(volumes, List.of(Topology.PROXY));

        assertEquals(ApplyResult.Status.FAILED, outcomes.getFirst().status(), detail(outcomes));
        // The status alone would be reached by an unhandled exception too. What the row has to say
        // is which directory was empty and what it costs, because the only other place this shows
        // up is a container refusing to start in the middle of a swap.
        assertTrue(
                String.valueOf(outcomes.getFirst().detail()).contains("refuse to start"),
                "the failure does not say what an empty standby costs: " + detail(outcomes));
        assertTrue(
                String.valueOf(outcomes.getFirst().detail()).contains("plugins"),
                "the failure does not name the directory it could not read: " + detail(outcomes));
    }

    // ---------------------------------------------------------------- fixtures

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
