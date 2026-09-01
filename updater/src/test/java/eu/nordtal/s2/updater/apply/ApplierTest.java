package eu.nordtal.s2.updater.apply;

import eu.nordtal.s2.updater.config.UpdaterSpec;
import eu.nordtal.s2.updater.http.Fetcher;
import eu.nordtal.s2.updater.plan.Change;
import eu.nordtal.s2.updater.plan.PackState;
import eu.nordtal.s2.updater.plan.UpdatePlan;
import eu.nordtal.s2.updater.source.Checksum;
import eu.nordtal.s2.updater.source.RemoteFile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 3: what happens on disk, and - the part worth the test - what happens on disk when it goes
 * wrong half way through.
 */
class ApplierTest {

    private static final String SHA1 = "6f1ed002ab5595859014ebf0951522d9d0f2ee34";

    @TempDir
    Path volumes;

    // ---------------------------------------------------------------- the good case

    @Test
    @DisplayName("an outdated jar is replaced and the one it supersedes is deleted")
    void replacesAndSupersedes() throws IOException {
        install("smp", "plugins/smp-0.1.0.jar");

        final ApplyResult result = apply(new Fake(), plan(
                outdated("smp", "smp", "smp-0.1.0.jar", "smp-0.2.0.jar")));

        assertTrue(Files.exists(volumes.resolve("smp/plugins/smp-0.2.0.jar")));
        assertFalse(Files.exists(volumes.resolve("smp/plugins/smp-0.1.0.jar")));

        final ApplyResult.Outcome outcome = outcome(result, "smp", "smp");
        assertEquals(ApplyResult.Status.DONE, outcome.status());
        assertNotNull(outcome.detail());
        assertTrue(outcome.detail().contains("removed smp-0.1.0.jar"), outcome.detail());
        assertTrue(result.restartWorthOffering());
    }

    @Test
    @DisplayName("a server jar goes into the entrypoint's cache, not into plugins")
    void serverJarsGoToTheServerCache() throws IOException {
        install("limbo", ".server/paper-26.2-119.jar");

        apply(new Fake(), plan(outdated("limbo", "paper", "paper-26.2-119.jar", "paper-26.2-121.jar")));

        assertTrue(Files.exists(volumes.resolve("limbo/.server/paper-26.2-121.jar")));
        assertFalse(Files.exists(volumes.resolve("limbo/.server/paper-26.2-119.jar")));
        assertFalse(Files.exists(volumes.resolve("limbo/plugins/paper-26.2-121.jar")));
    }

    @Test
    @DisplayName("a jar nothing accounts for survives a swap of the one beside it")
    void neverDeletesWhatItDoesNotOwn() throws IOException {
        install("smp", "plugins/smp-0.1.0.jar");
        install("smp", "plugins/SomeoneElsesPlugin-1.0.0.jar");

        apply(new Fake(), plan(outdated("smp", "smp", "smp-0.1.0.jar", "smp-0.2.0.jar")));

        assertTrue(Files.exists(volumes.resolve("smp/plugins/SomeoneElsesPlugin-1.0.0.jar")));
    }

    @Test
    @DisplayName("the staging directory is gone afterwards")
    void leavesNoStagingBehind() throws IOException {
        install("smp", "plugins/smp-0.1.0.jar");

        apply(new Fake(), plan(outdated("smp", "smp", "smp-0.1.0.jar", "smp-0.2.0.jar")));

        assertFalse(Files.exists(volumes.resolve("smp").resolve(Applier.STAGING)));
    }

    // ---------------------------------------------------------------- the failure cases

    @Test
    @DisplayName("a download failing part way through leaves the whole server exactly as it was")
    void nothingMovesUntilEverythingIsStaged() throws IOException {
        install("smp", "plugins/smp-0.1.0.jar");
        install("smp", "plugins/Chunky-Bukkit-1.5.2.jar");

        // The second of two downloads fails. Without two phases the SMP server would now be
        // running a new season jar against an old Chunky, which is a combination nobody chose.
        final ApplyResult result = apply(new Fake().failingOn("Chunky-Bukkit-1.5.3.jar"), plan(
                outdated("smp", "smp", "smp-0.1.0.jar", "smp-0.2.0.jar"),
                outdated("smp", "chunky", "Chunky-Bukkit-1.5.2.jar", "Chunky-Bukkit-1.5.3.jar")));

        assertTrue(Files.exists(volumes.resolve("smp/plugins/smp-0.1.0.jar")), "the old jar is still there");
        assertFalse(Files.exists(volumes.resolve("smp/plugins/smp-0.2.0.jar")), "the new jar was not placed");
        assertTrue(Files.exists(volumes.resolve("smp/plugins/Chunky-Bukkit-1.5.2.jar")));
        assertFalse(Files.exists(volumes.resolve("smp").resolve(Applier.STAGING)));

        assertEquals(ApplyResult.Status.FAILED, outcome(result, "smp", "smp").status());
        assertEquals(ApplyResult.Status.FAILED, outcome(result, "smp", "chunky").status());
        assertFalse(result.restartWorthOffering());
    }

    @Test
    @DisplayName("one unresolvable artefact skips its whole server, jars included")
    void aServerMovesTogetherOrNotAtAll() throws IOException {
        install("smp", "plugins/smp-0.1.0.jar");

        final ApplyResult result = apply(new Fake(), plan(
                outdated("smp", "smp", "smp-0.1.0.jar", "smp-0.2.0.jar"),
                Change.unresolved("smp", "packetevents", "Modrinth: connect timed out")));

        // DisplayTags is a required plugin of smp and PacketEvents is required under it. A partial
        // swap here is a server that does not start.
        assertTrue(Files.exists(volumes.resolve("smp/plugins/smp-0.1.0.jar")));
        assertFalse(Files.exists(volumes.resolve("smp/plugins/smp-0.2.0.jar")));
        assertEquals(ApplyResult.Status.SKIPPED, outcome(result, "smp", "smp").status());
        assertTrue(outcome(result, "smp", "smp").detail().contains("packetevents"));
        assertFalse(result.changedAnything());
    }

    @Test
    @DisplayName("one server failing does not stop another")
    void failuresDoNotSpreadBetweenServers() throws IOException {
        install("smp", "plugins/smp-0.1.0.jar");
        install("limbo", "plugins/limbo-0.1.0.jar");

        final ApplyResult result = apply(new Fake().failingOn("smp-0.2.0.jar"), plan(
                outdated("smp", "smp", "smp-0.1.0.jar", "smp-0.2.0.jar"),
                outdated("limbo", "limbo", "limbo-0.1.0.jar", "limbo-0.2.0.jar")));

        assertEquals(ApplyResult.Status.FAILED, outcome(result, "smp", "smp").status());
        assertEquals(ApplyResult.Status.DONE, outcome(result, "limbo", "limbo").status());
        assertTrue(Files.exists(volumes.resolve("limbo/plugins/limbo-0.2.0.jar")));
    }

    // ---------------------------------------------------------------- the pack

    @Test
    @DisplayName("the pack's two lines are written from the release, hash included")
    void writesThePack() throws IOException {
        install("network-control", "plugins/network-control-0.1.0.jar");
        writePackYml();

        final Change pack = new Change("network-control", "resource-pack", Change.Status.OUTDATED,
                "0000000000000000000000000000000000000000",
                new RemoteFile("resource-pack", "0.2.0", "nordtal-resource-pack-0.2.0.zip",
                        URI.create("https://github.com/nordtal/season-2/releases/download/v0.2.0/"
                                + "nordtal-resource-pack-0.2.0.zip"),
                        Checksum.sha1(SHA1)),
                null);

        final ApplyResult result = apply(new Fake(), plan(pack));

        final String written = Files.readString(PackState.fileIn(volumes.resolve("network-control")));
        assertTrue(written.contains("sha1: " + SHA1), written);
        assertTrue(written.contains("releases/download/v0.2.0/"), written);
        assertEquals(ApplyResult.Status.DONE, outcome(result, "network-control", "resource-pack").status());
        // The zip itself is never downloaded: the client fetches it, the proxy only describes it.
        assertFalse(Files.exists(volumes.resolve("network-control/plugins/nordtal-resource-pack-0.2.0.zip")));
    }

    @Test
    @DisplayName("the bot and the updater are reported and never moved")
    void doesNotTouchWhatHasNoVolume() throws IOException {
        final ApplyResult result = apply(new Fake(), plan(
                new Change(null, "discord-bot", Change.Status.MOUNT_MISSING, null,
                        remote("discord-bot", "discord-bot-0.2.0.jar"), "still a GHCR image")));

        assertEquals(ApplyResult.Status.SKIPPED, outcome(result, null, "discord-bot").status());
        assertFalse(result.changedAnything());
    }

    // ---------------------------------------------------------------- plumbing

    /** A {@link Fetcher} that writes a marker instead of downloading, and can be told to fail. */
    private static final class Fake implements Fetcher {

        private final List<String> fetched = new ArrayList<>();
        private String failOn;

        Fake failingOn(final String fileName) {
            this.failOn = fileName;
            return this;
        }

        @Override
        public void fetch(final RemoteFile file, final Path destination) throws IOException {
            if (file.fileName().equals(failOn)) {
                throw new IOException("pretend the CDN was down");
            }
            fetched.add(file.fileName());
            Files.createDirectories(destination.getParent());
            Files.writeString(destination, "downloaded " + file.fileName(), StandardCharsets.UTF_8);
        }
    }

    private ApplyResult apply(final Fetcher fetcher, final UpdatePlan plan) {
        final UpdaterSpec config = new UpdaterSpec() {
            @Override
            public String volumesRoot() {
                return volumes.toString();
            }
        };
        return new Applier(config, fetcher).apply(plan);
    }

    private static UpdatePlan plan(final Change... changes) {
        return new UpdatePlan(Instant.parse("2026-09-01T18:00:00Z"), "v0.2.0", false,
                List.of(changes), List.of());
    }

    private static Change outdated(final String service, final String artifact,
                                   final String installed, final String wanted) {
        return new Change(service, artifact, Change.Status.OUTDATED, installed,
                remote(artifact, wanted), null);
    }

    private static RemoteFile remote(final String artifact, final String fileName) {
        return new RemoteFile(artifact, "x", fileName,
                URI.create("https://example.invalid/" + fileName), null);
    }

    private void install(final String service, final String relative) throws IOException {
        final Path file = volumes.resolve(service).resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "old", StandardCharsets.UTF_8);
    }

    private void writePackYml() throws IOException {
        final Path file = PackState.fileIn(volumes.resolve("network-control"));
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                enabled: true
                url: https://github.com/nordtal/season-2/releases/download/v0.1.0/nordtal-resource-pack-0.1.0.zip
                sha1: 0000000000000000000000000000000000000000
                force: true
                """, StandardCharsets.UTF_8);
    }

    private static ApplyResult.Outcome outcome(final ApplyResult result, final String service,
                                               final String artifact) {
        return result.outcomes().stream()
                .filter(candidate -> candidate.artifact().equals(artifact))
                .filter(candidate -> service == null
                        ? candidate.service() == null
                        : service.equals(candidate.service()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no outcome for " + service + "/" + artifact));
    }
}
