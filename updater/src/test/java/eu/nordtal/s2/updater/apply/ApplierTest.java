package eu.nordtal.s2.updater.apply;

import eu.nordtal.s2.updater.config.UpdaterSpec;
import eu.nordtal.s2.updater.http.Fetcher;
import eu.nordtal.s2.updater.plan.Change;
import eu.nordtal.s2.updater.plan.PackState;
import eu.nordtal.s2.updater.plan.Topology;
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

        assertFalse(Files.exists(volumes.resolve("smp/plugins").resolve(Applier.STAGING)));
        assertFalse(Files.exists(volumes.resolve("smp").resolve(Applier.STAGING)),
                "and not at the volume root either, which is where it used to be");
    }

    @Test
    @DisplayName("staging happens inside the destination directory, not at the volume root")
    void theStagingDirectoryLivesBesideItsDestination() throws IOException {
        install("smp", "plugins/smp-0.1.0.jar");
        install("smp", ".server/paper-26.2-121.jar");

        // THE POINT OF THIS TEST. plugins/ is a bind mount in compose.yml and .server/ is not, so
        // the two are different filesystems on a real deployment. A rename across that boundary is
        // a copy, and a copy is not atomic - which nothing would ever have reported, because
        // Files.move falls back to copy-and-delete without complaining. The only way that stays
        // true is if staging is resolved per destination, so that is what is asserted.
        final Fake fetcher = new Fake();
        apply(fetcher, plan(
                outdated("smp", "smp", "smp-0.1.0.jar", "smp-0.2.0.jar"),
                outdated("smp", "paper", "paper-26.2-121.jar", "paper-26.2-125.jar")));

        assertEquals(volumes.resolve("smp/plugins").resolve(Applier.STAGING),
                parentOf(fetcher, "smp-0.2.0.jar"), "a plugin stages inside plugins/");
        assertEquals(volumes.resolve("smp/.server").resolve(Applier.STAGING),
                parentOf(fetcher, "paper-26.2-125.jar"), "the server jar stages inside .server/");

        assertTrue(Files.exists(volumes.resolve("smp/plugins/smp-0.2.0.jar")));
        assertTrue(Files.exists(volumes.resolve("smp/.server/paper-26.2-125.jar")));
        assertFalse(Files.exists(volumes.resolve("smp/plugins").resolve(Applier.STAGING)));
        assertFalse(Files.exists(volumes.resolve("smp/.server").resolve(Applier.STAGING)));
    }

    @Test
    @DisplayName("a staging directory left at the volume root by an older version is swept up")
    void theOldStagingLocationIsCleanedAway() throws IOException {
        install("smp", "plugins/smp-0.1.0.jar");
        install("smp", Applier.STAGING + "/smp-0.1.5.jar");

        apply(new Fake(), plan(outdated("smp", "smp", "smp-0.1.0.jar", "smp-0.2.0.jar")));

        // Nothing would ever look at it again, and a directory full of jars that no program reads
        // is a puzzle for whoever finds it rather than a harmless leftover.
        assertFalse(Files.exists(volumes.resolve("smp").resolve(Applier.STAGING)));
    }

    @Test
    @DisplayName("a file left in staging by a run that died is not installed")
    void staleStagedFilesAreNeverReused() throws IOException {
        install("smp", "plugins/smp-0.1.0.jar");
        install("smp", "plugins/" + Applier.STAGING + "/smp-0.2.0.jar");

        apply(new Fake(), plan(outdated("smp", "smp", "smp-0.1.0.jar", "smp-0.2.0.jar")));

        // "old" is what install() writes. A run that died between the two phases leaves exactly
        // this, and re-using it would install a jar this run never verified.
        assertEquals("downloaded smp-0.2.0.jar",
                Files.readString(volumes.resolve("smp/plugins/smp-0.2.0.jar")));
    }

    @Test
    @DisplayName("the staging directory is never read back as an installed jar")
    void stagingIsInvisibleToTheScan() throws IOException {
        install("smp", "plugins/smp-0.1.0.jar");
        install("smp", "plugins/" + Applier.STAGING + "/smp-9.9.9.jar");

        // It sits inside plugins/ now, so this is the assumption everything above rests on: a
        // Paper server loads only jars directly in plugins/, and Installation only takes regular
        // files. If either stopped being true, the SMP would try to load a half-downloaded jar.
        final var installed = eu.nordtal.s2.updater.plan.Installation
                .scan("smp", volumes.resolve("smp")).plugins();

        assertEquals(List.of("smp-0.1.0.jar"), installed.stream()
                .map(eu.nordtal.s2.updater.plan.Installation.Jar::fileName).toList());
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
        assertFalse(Files.exists(volumes.resolve("smp/plugins").resolve(Applier.STAGING)));

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
    @DisplayName("an artefact with no build for this version does not hold its server back")
    void anUnsupportedArtefactDoesNotSkipTheServer() throws IOException {
        install("smp", "plugins/smp-0.1.0.jar");

        // The same shape as the case above it and the opposite outcome, which is the whole reason
        // the status is not UNRESOLVED: CoreProtect has no 26.2 build, and a failure row would have
        // meant the SMP's own jar was never installed - every run, for as long as that lasted.
        final ApplyResult result = apply(new Fake(), plan(
                outdated("smp", "smp", "smp-0.1.0.jar", "smp-0.2.0.jar"),
                Change.unsupported("smp", "coreprotect", "no stable release for this platform")));

        assertTrue(Files.exists(volumes.resolve("smp/plugins/smp-0.2.0.jar")));
        assertEquals(ApplyResult.Status.DONE, outcome(result, "smp", "smp").status());

        // Its own word. UNCHANGED is a claim about a file that is there and nothing is, and SKIPPED
        // is the whole-service refusal, which would put "nothing was installed, and not because
        // everything was current" under a run that installed the season jar.
        assertEquals(ApplyResult.Status.UNSUPPORTED, outcome(result, "smp", "coreprotect").status());
        assertFalse(result.skippedAnything());
        assertFalse(result.hasFailures());
        assertTrue(result.changedAnything());
    }

    @Test
    @DisplayName("a server jar that could not be resolved does not hold the plugins back")
    void anUnresolvedServerJarDoesNotBlockThePlugins() throws IOException {
        install("smp", "plugins/smp-0.1.0.jar");
        install("smp", ".server/paper-26.2-121.jar");

        // Fill is down. The plugins are compiled against 26.2, not against build 121, and 121 is
        // a build that runs - so there is nothing for a Fill outage to protect the plugins from.
        final ApplyResult result = apply(new Fake(), plan(
                outdated("smp", "smp", "smp-0.1.0.jar", "smp-0.2.0.jar"),
                Change.unresolved("smp", "paper", "PaperMC Fill: connect timed out")));

        assertTrue(Files.exists(volumes.resolve("smp/plugins/smp-0.2.0.jar")));
        assertFalse(Files.exists(volumes.resolve("smp/plugins/smp-0.1.0.jar")));
        assertTrue(Files.exists(volumes.resolve("smp/.server/paper-26.2-121.jar")), "the build stays");
        assertEquals(ApplyResult.Status.DONE, outcome(result, "smp", "smp").status());
        assertEquals(ApplyResult.Status.SKIPPED, outcome(result, "smp", "paper").status());
        assertTrue(outcome(result, "smp", "paper").detail().contains("Fill"));
        assertTrue(result.changedAnything());
        assertTrue(result.skippedAnything());
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
    @DisplayName("the bot's jar goes into the root of its own volume, not into a plugins folder")
    void installsAStandaloneJarInTheVolumeRoot() throws IOException {
        Files.createDirectories(volumes.resolve("discord-bot"));
        Files.writeString(volumes.resolve("discord-bot/discord-bot-0.1.0.jar"), "old");

        final ApplyResult result = apply(new Fake(), plan(
                new Change("discord-bot", "discord-bot", Change.Status.OUTDATED,
                        "discord-bot-0.1.0.jar", remote("discord-bot", "discord-bot-0.2.0.jar"), null)));

        assertEquals(ApplyResult.Status.DONE, outcome(result, "discord-bot", "discord-bot").status());
        assertTrue(Files.exists(volumes.resolve("discord-bot/discord-bot-0.2.0.jar")));
        assertFalse(Files.exists(volumes.resolve("discord-bot/discord-bot-0.1.0.jar")),
                "the superseded jar goes, by the same prefix rule as every plugin");
        assertFalse(Files.exists(volumes.resolve("discord-bot/plugins")),
                "there is no plugins folder here and none is created");
    }

    @Test
    @DisplayName("the updater installs its own jar, for the next start to pick up")
    void installsItsOwnJar() throws IOException {
        Files.createDirectories(volumes.resolve("updater"));

        final ApplyResult result = apply(new Fake(), plan(
                new Change("updater", "updater", Change.Status.MISSING, null,
                        remote("updater", "updater-0.2.0.jar"), null)));

        assertEquals(ApplyResult.Status.DONE, outcome(result, "updater", "updater").status());
        assertTrue(Files.exists(volumes.resolve("updater/updater-0.2.0.jar")),
                "it lands in the volume; the process running right now carries on with the old one"
                        + " until the restart, which is the only way this module's version moves");
    }

    @Test
    @DisplayName("a volume that is not mounted is skipped whole, never created")
    void doesNotCreateAVolumeThatIsNotThere() {
        final ApplyResult result = apply(new Fake(), plan(
                new Change("discord-bot", "discord-bot", Change.Status.MOUNT_MISSING, null,
                        remote("discord-bot", "discord-bot-0.2.0.jar"),
                        "/volumes/discord-bot is not mounted in this container")));

        assertEquals(ApplyResult.Status.SKIPPED, outcome(result, "discord-bot", "discord-bot").status());
        assertFalse(result.changedAnything());
        assertFalse(Files.exists(volumes.resolve("discord-bot")));
    }

    @Test
    @DisplayName("a run where everything was skipped does not read as a run where nothing was needed")
    void skippedIsNotTheSameAsCurrent() {
        // Found on a real container run, 2026-09-01: every volume unmounted, every row skipped,
        // and the report closed with "Nothing needed doing." - which is the sentence that lets
        // somebody shut the report believing the network is up to date.
        final ApplyResult result = apply(new Fake(), plan(
                Change.unresolved("smp", "packetevents", "Modrinth: connect timed out"),
                new Change("smp", "smp", Change.Status.OUTDATED, "smp-0.1.0.jar",
                        remote("smp", "smp-0.2.0.jar"), null)));

        assertTrue(result.skippedAnything());
        assertFalse(result.changedAnything());
        assertFalse(result.hasFailures());

        final String rendered = eu.nordtal.s2.updater.plan.Report.render(result);
        assertFalse(rendered.contains("Nothing needed doing"), rendered);
        assertTrue(rendered.contains("not because everything was current"), rendered);
    }

    @Test
    @DisplayName("B4: a bootstrap whose season jar is unresolved installs nothing for that server")
    void anIncompleteServerIsNotPartlyFilled() {
        // The first real deployment. GitHub answered 403 for the season release while Modrinth
        // answered fine for PacketEvents and Chunky, so smp's folder was filled with its two
        // third-party plugins and no season - and the entrypoint's guard, which only counted jars,
        // let it start. Three other servers were caught because their folders stayed empty.
        //
        // The plan goes through onlyMissing() here rather than being built by hand, because that
        // filter is where the row used to disappear: this asserts the whole bootstrap path, not
        // just the Applier.
        final UpdatePlan bootstrap = plan(
                Change.unresolved("smp", "smp", "could not read nordtal/season-2@latest: HTTP 403"),
                new Change("smp", "packetevents", Change.Status.MISSING, null,
                        remote("packetevents", "packetevents-spigot-2.13.0.jar"), null),
                new Change("smp", "chunky", Change.Status.MISSING, null,
                        remote("chunky", "Chunky-Bukkit-1.5.3.jar"), null))
                .onlyMissing();

        final ApplyResult result = apply(new Fake(), bootstrap);

        assertFalse(Files.exists(volumes.resolve("smp/plugins/packetevents-spigot-2.13.0.jar")),
                "PacketEvents was installed beside a season that could not be resolved. That is the"
                        + " one shape of half-filled volume the empty-plugins guard cannot see.");
        assertFalse(Files.exists(volumes.resolve("smp/plugins/Chunky-Bukkit-1.5.3.jar")));
        assertFalse(result.changedAnything());
        assertTrue(result.skippedAnything());

        final String rendered = eu.nordtal.s2.updater.plan.Report.render(result);
        assertFalse(rendered.contains("Everything asked for was done"), rendered);
        assertTrue(rendered.contains("not because everything was current"), rendered);
    }

    @Test
    @DisplayName("M1: a pack that cannot be resolved falls back and does not hold the jars back")
    void anUnresolvablePackDoesNotBlockTheProxy() throws IOException {
        install("network-control", "plugins/network-control-0.1.0.jar");

        // A release that published no .sha1 beside the pack zip. This used to skip the whole
        // service - the proxy plugin and the Velocity jar with it - while the three backends
        // updated regardless, which is precisely the split network the all-or-nothing rule exists
        // to prevent.
        final ApplyResult result = apply(new Fake(), plan(
                Change.unresolved("network-control", Topology.RESOURCE_PACK,
                        "the release published no .sha1 asset"),
                outdated("network-control", "network-control",
                        "network-control-0.1.0.jar", "network-control-0.2.0.jar")));

        assertTrue(Files.exists(volumes.resolve("network-control/plugins/network-control-0.2.0.jar")),
                "the proxy plugin was held back because the pack could not be checked");

        final ApplyResult.Outcome pack = outcome(result, "network-control", Topology.RESOURCE_PACK);
        assertEquals(ApplyResult.Status.SKIPPED, pack.status(),
                "a pack that could not be checked must not read as UNCHANGED - the client is still"
                        + " being sent the previous one, and that is a fallback, not a no-op");
        assertNotNull(pack.detail());
        assertTrue(pack.detail().contains("pack.yml was left alone"), pack.detail());
    }

    @Test
    @DisplayName("M1: the pack still gets a row when the service really is blocked")
    void aBlockedServiceStillReportsItsPack() {
        // The early return used to skip applyPack entirely, so a run that skipped this service said
        // nothing at all about what the client is being sent - the one row here a player can see.
        final ApplyResult result = apply(new Fake(), plan(
                Change.unresolved("network-control", "network-control", "GitHub answered 403"),
                new Change("network-control", Topology.RESOURCE_PACK, Change.Status.UP_TO_DATE,
                        "abc123", null, null)));

        final ApplyResult.Outcome pack = outcome(result, "network-control", Topology.RESOURCE_PACK);
        assertNotNull(pack, "the pack row vanished from a report for a service that was skipped");
    }

    // ---------------------------------------------------------------- plumbing

    /** A {@link Fetcher} that writes a marker instead of downloading, and can be told to fail. */
    private static final class Fake implements Fetcher {

        private final List<String> fetched = new ArrayList<>();
        private final List<Path> destinations = new ArrayList<>();
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
            destinations.add(destination);
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

            @Override
            public BackupSpec backup() {
                // Defaults throughout: this test is not about a backup, and BackupSpec's own
                // defaults are the production ones.
                return new BackupSpec() {
                };
            }

            @Override
            public ArcaneSpec arcane() {
                // Every setting on it has a default and none of them matters here: an empty
                // base-url means "no restart is possible", which is exactly right for a test
                // about resolving and installing files.
                return new ArcaneSpec() {
                };
            }

        };
        return new Applier(config, fetcher).apply(plan);
    }

    private static UpdatePlan plan(final Change... changes) {
        return new UpdatePlan(Instant.parse("2026-09-01T18:00:00Z"), "v0.2.0", false,
                List.of(changes), List.of(), List.of());
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

    /** Where the fetcher was asked to put one file - the staging directory, by definition. */
    private static Path parentOf(final Fake fetcher, final String fileName) {
        return fetcher.destinations.stream()
                .filter(candidate -> candidate.getFileName().toString().equals(fileName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(fileName + " was never fetched"))
                .getParent();
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
