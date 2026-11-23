package eu.nordtal.season.stewardagent.apply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.stewardagent.plan.Installation;
import eu.nordtal.season.stewardagent.topology.DeclaredTopology;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a standby's {@code plugins/} holds after a run.
 *
 * A replacement comes up on the same jars and data files as the service it replaces.
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
    void theStandbyRunsTheServerJarItsServiceRuns() throws IOException {
        // The entrypoint runs the newest jar in .server, so a standby left on an older Minecraft refuses every player.
        write("limbo/plugins/limbo-0.9.3.jar", "new");
        write("limbo/.server/paper-26.3-249.jar", "26.3");
        write("limbo-standby/.server/paper-26.2-129.jar", "26.2");

        final List<ApplyResult.Outcome> outcomes =
                Standbys.fill(volumes, List.of(Topology.LIMBO), DeclaredTopology.topology());

        assertTrue(
                outcomes.stream().allMatch(outcome -> outcome.status() == ApplyResult.Status.DONE), detail(outcomes));
        assertEquals("26.3", read("limbo-standby/.server/paper-26.3-249.jar"));
        assertFalse(
                Files.exists(volumes.resolve("limbo-standby/.server/paper-26.2-129.jar")),
                "the standby keeps a jar its service no longer runs");
        assertTrue(
                Standbys.fill(volumes, List.of(Topology.LIMBO), DeclaredTopology.topology()).stream()
                        .allMatch(outcome -> outcome.status() == ApplyResult.Status.UNCHANGED),
                "a second fill copies the server jar again");
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
    void symbolicLinksOnEitherSideAreSkippedWithoutTouchingTheirTargets() throws IOException {
        write("proxy/plugins/proxy-0.9.3.jar", "new");
        write("proxy/plugins/proxy/config.json", "{}");
        write("outside/source-file", "source secret");
        write("outside/source-directory/secret", "source directory secret");
        write("outside/standby-file", "standby secret");
        write("outside/standby-directory/keep", "keep");
        final Path source = volumes.resolve("proxy/plugins");
        final Path standby = volumes.resolve("proxy-standby/plugins");
        Files.createDirectories(standby);
        Files.createSymbolicLink(source.resolve("file-link"), volumes.resolve("outside/source-file"));
        Files.createSymbolicLink(source.resolve("directory-link"), volumes.resolve("outside/source-directory"));
        Files.createSymbolicLink(standby.resolve("proxy-0.9.3.jar"), volumes.resolve("outside/standby-file"));
        Files.createSymbolicLink(standby.resolve("proxy"), volumes.resolve("outside/standby-directory"));
        Files.createSymbolicLink(standby.resolve("old-file-link"), volumes.resolve("outside/standby-file"));
        Files.createSymbolicLink(standby.resolve("old-directory-link"), volumes.resolve("outside/standby-directory"));

        Standbys.fill(volumes, List.of(Topology.PROXY), DeclaredTopology.topology());

        assertFalse(Files.exists(standby.resolve("file-link"), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(standby.resolve("directory-link"), LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isSymbolicLink(standby.resolve("proxy-0.9.3.jar")));
        assertTrue(Files.isSymbolicLink(standby.resolve("proxy")));
        assertTrue(Files.isSymbolicLink(standby.resolve("old-file-link")));
        assertTrue(Files.isSymbolicLink(standby.resolve("old-directory-link")));
        assertEquals("source secret", read("outside/source-file"));
        assertEquals("source directory secret", read("outside/source-directory/secret"));
        assertEquals("standby secret", read("outside/standby-file"));
        assertEquals("keep", read("outside/standby-directory/keep"));
    }

    @Test
    void aPluginsDirectoryThatIsASymbolicLinkOnEitherSideIsNeitherReadNorWritten() throws IOException {
        write("outside/secrets", "secret");
        write("outside/world/level.dat", "world");
        write("limbo/plugins/limbo-0.9.3.jar", "new");
        Files.createDirectories(volumes.resolve("proxy"));
        Files.createDirectories(volumes.resolve("proxy-standby"));
        Files.createSymbolicLink(volumes.resolve("proxy/plugins"), volumes.resolve("outside"));
        Files.createDirectories(volumes.resolve("limbo-standby"));
        Files.createSymbolicLink(volumes.resolve("limbo-standby/plugins"), volumes.resolve("outside/world"));

        final List<ApplyResult.Outcome> outcomes =
                Standbys.fill(volumes, List.of(Topology.PROXY, Topology.LIMBO), DeclaredTopology.topology());

        assertEquals(2, outcomes.size(), detail(outcomes));
        assertTrue(
                outcomes.stream().allMatch(outcome -> outcome.status() == ApplyResult.Status.FAILED), detail(outcomes));
        assertFalse(Files.exists(volumes.resolve("proxy-standby/plugins/secrets"), LinkOption.NOFOLLOW_LINKS));
        assertEquals("world", read("outside/world/level.dat"));
        assertFalse(Files.exists(volumes.resolve("outside/world/limbo-0.9.3.jar"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void aServiceDirectoryThatIsALinkToAnythingButADirectoryFailsItsRow() throws IOException {
        write("outside/secret", "secret");
        Files.createDirectories(volumes.resolve("limbo"));
        Files.createDirectories(volumes.resolve("proxy"));
        mounted(DeclaredTopology.topology().standbyOf(Topology.LIMBO).orElseThrow());
        mounted(DeclaredTopology.topology().standbyOf(Topology.PROXY).orElseThrow());
        write("limbo/plugins/limbo-0.9.3.jar", "new");
        Files.createSymbolicLink(volumes.resolve("limbo/.server"), volumes.resolve("outside/secret"));
        Files.createSymbolicLink(volumes.resolve("proxy/plugins"), volumes.resolve("outside/secret"));

        final List<ApplyResult.Outcome> outcomes =
                Standbys.fill(volumes, List.of(Topology.LIMBO, Topology.PROXY), DeclaredTopology.topology());

        assertEquals(
                Set.of(
                        "limbo-standby " + Installation.PLUGINS + " DONE",
                        "limbo-standby " + Installation.SERVER_CACHE + " FAILED",
                        "proxy-standby " + Installation.PLUGINS + " FAILED"),
                outcomes.stream()
                        .map(outcome -> outcome.service() + " " + outcome.artifact() + " " + outcome.status())
                        .collect(Collectors.toSet()),
                "a linked .server is skipped as if the service had no cache: " + detail(outcomes));
        assertTrue(
                outcomes.stream()
                        .filter(outcome -> outcome.status() == ApplyResult.Status.FAILED)
                        .allMatch(outcome -> String.valueOf(outcome.detail()).contains("symbolic link")),
                "a failed row does not name the link: " + detail(outcomes));
    }

    @Test
    void aSourceDirectorySwappedForALinkAtTheOpenIsNotCopiedFrom() throws IOException {
        write("proxy/plugins/proxy-0.9.3.jar", "new");
        write("proxy/plugins/proxy/config.json", "{}");
        write("outside/secret", "secret");
        mounted(DeclaredTopology.topology().standbyOf(Topology.PROXY).orElseThrow());

        final List<ApplyResult.Outcome> outcomes = Standbys.fill(
                volumes,
                List.of(Topology.PROXY),
                DeclaredTopology.topology(),
                swapAtOpen("proxy/plugins/proxy", "outside"));

        assertFalse(
                Files.exists(volumes.resolve("proxy-standby/plugins/proxy/secret"), LinkOption.NOFOLLOW_LINKS),
                "the agent followed the link and copied a file from outside the service's plugins/");
        assertEquals("secret", read("outside/secret"));
        assertEquals(ApplyResult.Status.FAILED, outcomes.getFirst().status(), detail(outcomes));
    }

    @Test
    void aStandbyDirectorySwappedForALinkAtTheOpenIsNeitherWrittenNorEmptied() throws IOException {
        write("proxy/plugins/proxy/config.json", "{}");
        write("proxy-standby/plugins/proxy/stale.json", "stale");
        write("outside/keep", "keep");

        final List<ApplyResult.Outcome> outcomes = Standbys.fill(
                volumes,
                List.of(Topology.PROXY),
                DeclaredTopology.topology(),
                swapAtOpen("proxy-standby/plugins/proxy", "outside"));

        assertEquals(
                "keep", read("outside/keep"), "the agent followed the link and deleted a file outside the standby");
        assertFalse(
                Files.exists(volumes.resolve("outside/config.json"), LinkOption.NOFOLLOW_LINKS),
                "the agent followed the link and wrote a file outside the standby");
        assertEquals(ApplyResult.Status.FAILED, outcomes.getFirst().status(), detail(outcomes));
    }

    @Test
    void aDirectorySwappedForALinkWhileItIsDeletedKeepsTheLinkTargetsFiles() throws IOException {
        write("proxy/plugins/proxy-0.9.3.jar", "new");
        write("proxy-standby/plugins/superseded/config.json", "old");
        write("outside/keep", "keep");

        final List<ApplyResult.Outcome> outcomes = Standbys.fill(
                volumes,
                List.of(Topology.PROXY),
                DeclaredTopology.topology(),
                swapAtOpen("proxy-standby/plugins/superseded", "outside"));

        assertEquals(
                "keep", read("outside/keep"), "the agent followed the link and deleted a file outside the standby");
        assertEquals(ApplyResult.Status.FAILED, outcomes.getFirst().status(), detail(outcomes));
    }

    @Test
    void aNewDirectoryIsMadeInTheRootOfTheTreeItLandsIn() throws IOException {
        // plugins/ is a mount of its own beside the volume, and a rename from one mount into the other fails.
        write("limbo/plugins/limbo/config.json", "{}");
        write("limbo/.server/libraries/paper.jar", "26.3");
        mounted(DeclaredTopology.topology().standbyOf(Topology.LIMBO).orElseThrow());
        final List<Path> made = new ArrayList<>();

        final List<ApplyResult.Outcome> outcomes =
                Standbys.fill(volumes, List.of(Topology.LIMBO), DeclaredTopology.topology(), opening -> {
                    if (opening.getFileName().toString().startsWith(Applier.STAGING)) {
                        made.add(opening.getParent());
                    }
                });

        assertTrue(
                outcomes.stream().allMatch(outcome -> outcome.status() == ApplyResult.Status.DONE), detail(outcomes));
        assertEquals(
                List.of(volumes.resolve("limbo-standby/plugins"), volumes.resolve("limbo-standby")),
                made,
                "a new directory is made outside the mount it is moved into");
        assertEquals("{}", read("limbo-standby/plugins/limbo/config.json"));
        assertEquals("26.3", read("limbo-standby/.server/libraries/paper.jar"));
    }

    @Test
    void aNewDirectorySwappedForALinkBeforeItIsOpenedFailsTheRowAndTouchesNothingOutside() throws IOException {
        write("proxy/plugins/proxy/config.json", "{}");
        write("outside/keep", "keep");
        mounted(DeclaredTopology.topology().standbyOf(Topology.PROXY).orElseThrow());

        final List<ApplyResult.Outcome> outcomes = Standbys.fill(
                volumes,
                List.of(Topology.PROXY),
                DeclaredTopology.topology(),
                swapAt(opening -> opening.getFileName().toString().startsWith(Applier.STAGING), "outside"));

        assertEquals(ApplyResult.Status.FAILED, outcomes.getFirst().status(), detail(outcomes));
        assertEquals(
                "keep", read("outside/keep"), "the agent followed the link and deleted a file outside the standby");
        assertFalse(
                Files.exists(volumes.resolve("outside/config.json"), LinkOption.NOFOLLOW_LINKS),
                "the agent followed the link and wrote a file outside the standby");
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
    void theStandbyKeepsItsOwnBStatsSettingsSoMetricsStayOff() throws IOException {
        // The entrypoint turns bStats off at each start, and the live server's file can predate that start.
        write("limbo/plugins/limbo-0.9.3.jar", "new");
        write("limbo/plugins/bStats/config.yml", "serverUuid: live\nenabled: true\n");
        write("limbo-standby/plugins/bStats/config.yml", "serverUuid: standby\nenabled: false\n");

        final List<ApplyResult.Outcome> outcomes =
                Standbys.fill(volumes, List.of(Topology.LIMBO), DeclaredTopology.topology());

        assertEquals(ApplyResult.Status.DONE, outcomes.getFirst().status(), detail(outcomes));
        assertEquals("new", read("limbo-standby/plugins/limbo-0.9.3.jar"));
        assertEquals(
                "serverUuid: standby\nenabled: false\n",
                read("limbo-standby/plugins/bStats/config.yml"),
                "the live server's bStats settings were copied over the standby's");
    }

    @Test
    void aStandbyWithoutBStatsSettingsGetsNoneFromTheLiveServer() throws IOException {
        write("proxy/plugins/proxy-0.9.3.jar", "new");
        write("proxy/plugins/bStats/config.txt", "enabled=true\n");
        mounted(DeclaredTopology.topology().standbyOf(Topology.PROXY).orElseThrow());

        Standbys.fill(volumes, List.of(Topology.PROXY), DeclaredTopology.topology());

        assertFalse(
                Files.exists(volumes.resolve("proxy-standby/plugins/bStats"), LinkOption.NOFOLLOW_LINKS),
                "the standby's entrypoint writes its own bStats settings at its next start");
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

        // An older stack without this feature must not get a skipped line in the report.
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
        // The status alone could be an unhandled exception, so the row names the empty directory.
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

    /**
     * Renames a directory away and puts a symbolic link to another in its place, the first time the agent opens it.
     *
     * This is what code in a service's container can do between the agent's checks and its open.
     */
    private Consumer<Path> swapAtOpen(final String directory, final String linkTarget) {
        return swapAt(volumes.resolve(directory)::equals, linkTarget);
    }

    /** Swaps the first directory the agent opens that matches, as {@link #swapAtOpen} does. */
    private Consumer<Path> swapAt(final Predicate<Path> directory, final String linkTarget) {
        final AtomicBoolean done = new AtomicBoolean();
        return opening -> {
            if (!directory.test(opening) || done.getAndSet(true)) {
                return;
            }
            try {
                Files.move(opening, opening.resolveSibling(opening.getFileName() + ".moved"));
                Files.createSymbolicLink(opening, volumes.resolve(linkTarget));
            } catch (final IOException failed) {
                throw new UncheckedIOException(failed);
            }
        };
    }

    private String read(final String relative) throws IOException {
        return Files.readString(volumes.resolve(relative), StandardCharsets.UTF_8);
    }

    private static String detail(final List<ApplyResult.Outcome> outcomes) {
        return String.valueOf(outcomes);
    }
}
