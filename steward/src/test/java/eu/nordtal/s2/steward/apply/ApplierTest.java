package eu.nordtal.s2.steward.apply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.steward.config.BackupSpec;
import eu.nordtal.s2.steward.config.StewardSpec;
import eu.nordtal.s2.steward.plan.Change;
import eu.nordtal.s2.steward.plan.PackState;
import eu.nordtal.s2.steward.plan.Topology;
import eu.nordtal.s2.steward.plan.UpdatePlan;
import eu.nordtal.s2.steward.source.Checksum;
import eu.nordtal.s2.steward.source.Fetcher;
import eu.nordtal.s2.steward.source.RemoteFile;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Step 3 of a run: what happens on disk, above all when it fails half way through. */
class ApplierTest {

    private static final String SHA1 = "6f1ed002ab5595859014ebf0951522d9d0f2ee34";

    @TempDir
    Path volumes;

    @Test
    void anOutdatedJarIsReplacedAndTheOneItSupersedesIsDeleted() throws IOException {
        install("smp", "plugins/smp-0.1.0.jar");

        final ApplyResult result = apply(new Fake(), plan(outdated("smp", "smp", "smp-0.1.0.jar", "smp-0.2.0.jar")));

        assertTrue(Files.exists(volumes.resolve("smp/plugins/smp-0.2.0.jar")));
        assertFalse(Files.exists(volumes.resolve("smp/plugins/smp-0.1.0.jar")));

        final ApplyResult.Outcome outcome = outcome(result, "smp", "smp");
        assertEquals(ApplyResult.Status.DONE, outcome.status());
        assertNotNull(outcome.detail());
        assertTrue(outcome.detail().contains("removed smp-0.1.0.jar"), outcome.detail());
        assertTrue(result.restartWorthOffering());
    }

    @Test
    void aSeasonJarTheReleaseDoesNotCarryStaysAndThePluginsBesideItStillMove() throws IOException {
        install("smp", "plugins/smp-0.9.5.jar");
        install("smp", "plugins/packetevents-spigot-2.13.0.jar");

        final ApplyResult result = apply(
                new Fake(),
                plan(
                        new Change(
                                "smp",
                                "smp",
                                Change.Status.NOT_IN_RELEASE,
                                "smp-0.9.5.jar",
                                null,
                                "release v0.9.5 carries no smp-<version>.jar"),
                        outdated(
                                "smp",
                                "packetevents",
                                "packetevents-spigot-2.13.0.jar",
                                "packetevents-spigot-2.14.0.jar")));

        assertTrue(Files.exists(volumes.resolve("smp/plugins/smp-0.9.5.jar")));
        assertTrue(Files.exists(volumes.resolve("smp/plugins/packetevents-spigot-2.14.0.jar")));
        assertEquals(
                ApplyResult.Status.DONE, outcome(result, "smp", "packetevents").status());
        assertEquals(ApplyResult.Status.UNCHANGED, outcome(result, "smp", "smp").status());
    }

    @Test
    void aServerJarGoesIntoTheEntrypointsCacheNotIntoPlugins() throws IOException {
        install("limbo", ".server/paper-26.2-119.jar");

        apply(new Fake(), plan(outdated("limbo", "paper", "paper-26.2-119.jar", "paper-26.2-121.jar")));

        assertTrue(Files.exists(volumes.resolve("limbo/.server/paper-26.2-121.jar")));
        assertFalse(Files.exists(volumes.resolve("limbo/.server/paper-26.2-119.jar")));
        assertFalse(Files.exists(volumes.resolve("limbo/plugins/paper-26.2-121.jar")));
    }

    @Test
    void aJarNothingAccountsForSurvivesASwapOfTheOneBesideIt() throws IOException {
        install("smp", "plugins/smp-0.1.0.jar");
        install("smp", "plugins/SomeoneElsesPlugin-1.0.0.jar");

        apply(new Fake(), plan(outdated("smp", "smp", "smp-0.1.0.jar", "smp-0.2.0.jar")));

        assertTrue(Files.exists(volumes.resolve("smp/plugins/SomeoneElsesPlugin-1.0.0.jar")));
    }

    @Test
    void theStagingDirectoryIsGoneAfterwards() throws IOException {
        install("smp", "plugins/smp-0.1.0.jar");

        apply(new Fake(), plan(outdated("smp", "smp", "smp-0.1.0.jar", "smp-0.2.0.jar")));

        assertFalse(Files.exists(volumes.resolve("smp/plugins").resolve(Applier.STAGING)));
        assertFalse(
                Files.exists(volumes.resolve("smp").resolve(Applier.STAGING)),
                "and not at the volume root either, which is where it used to be");
    }

    @Test
    void stagingHappensInsideTheDestinationDirectoryNotAtTheVolumeRoot() throws IOException {
        install("smp", "plugins/smp-0.1.0.jar");
        install("smp", ".server/paper-26.2-121.jar");

        // plugins/ is a bind mount and .server/ is not, so a rename across that boundary is a non-atomic copy.
        final Fake fetcher = new Fake();
        apply(
                fetcher,
                plan(
                        outdated("smp", "smp", "smp-0.1.0.jar", "smp-0.2.0.jar"),
                        outdated("smp", "paper", "paper-26.2-121.jar", "paper-26.2-125.jar")));

        assertEquals(
                volumes.resolve("smp/plugins").resolve(Applier.STAGING),
                parentOf(fetcher, "smp-0.2.0.jar"),
                "a plugin stages inside plugins/");
        assertEquals(
                volumes.resolve("smp/.server").resolve(Applier.STAGING),
                parentOf(fetcher, "paper-26.2-125.jar"),
                "the server jar stages inside .server/");

        assertTrue(Files.exists(volumes.resolve("smp/plugins/smp-0.2.0.jar")));
        assertTrue(Files.exists(volumes.resolve("smp/.server/paper-26.2-125.jar")));
        assertFalse(Files.exists(volumes.resolve("smp/plugins").resolve(Applier.STAGING)));
        assertFalse(Files.exists(volumes.resolve("smp/.server").resolve(Applier.STAGING)));
    }

    @Test
    void aStagingDirectoryLeftAtTheVolumeRootByAnOlderVersionIsSweptUp() throws IOException {
        install("smp", "plugins/smp-0.1.0.jar");
        install("smp", Applier.STAGING + "/smp-0.1.5.jar");

        apply(new Fake(), plan(outdated("smp", "smp", "smp-0.1.0.jar", "smp-0.2.0.jar")));

        // A directory of jars nothing reads is a puzzle, not a harmless leftover.
        assertFalse(Files.exists(volumes.resolve("smp").resolve(Applier.STAGING)));
    }

    @Test
    void aFileLeftInStagingByARunThatDiedIsNotInstalled() throws IOException {
        install("smp", "plugins/smp-0.1.0.jar");
        install("smp", "plugins/" + Applier.STAGING + "/smp-0.2.0.jar");

        apply(new Fake(), plan(outdated("smp", "smp", "smp-0.1.0.jar", "smp-0.2.0.jar")));

        // "old" is what install() writes; a run dying between the two phases must not reuse a jar it never verified.
        assertEquals("downloaded smp-0.2.0.jar", Files.readString(volumes.resolve("smp/plugins/smp-0.2.0.jar")));
    }

    @Test
    void theStagingDirectoryIsNeverReadBackAsAnInstalledJar() throws IOException {
        install("smp", "plugins/smp-0.1.0.jar");
        install("smp", "plugins/" + Applier.STAGING + "/smp-9.9.9.jar");

        // A Paper server loads only jars directly in plugins/, and Installation only takes regular files.
        final var installed = eu.nordtal.s2.steward.plan.Installation.scan("smp", volumes.resolve("smp"))
                .plugins();

        assertEquals(
                List.of("smp-0.1.0.jar"),
                installed.stream()
                        .map(eu.nordtal.s2.steward.plan.Installation.Jar::fileName)
                        .toList());
    }

    @Test
    void aDownloadFailingPartWayThroughLeavesTheWholeServerExactlyAsItWas() throws IOException {
        install("smp", "plugins/smp-0.1.0.jar");
        install("smp", "plugins/voicechat-bukkit-2.6.21.jar");

        // The second of two downloads fails: nothing must run a new season jar against an old Simple Voice Chat.
        final ApplyResult result = apply(
                new Fake().failingOn("voicechat-bukkit-2.6.23.jar"),
                plan(
                        outdated("smp", "smp", "smp-0.1.0.jar", "smp-0.2.0.jar"),
                        outdated("smp", "voicechat", "voicechat-bukkit-2.6.21.jar", "voicechat-bukkit-2.6.23.jar")));

        assertTrue(Files.exists(volumes.resolve("smp/plugins/smp-0.1.0.jar")), "the old jar is still there");
        assertFalse(Files.exists(volumes.resolve("smp/plugins/smp-0.2.0.jar")), "the new jar was not placed");
        assertTrue(Files.exists(volumes.resolve("smp/plugins/voicechat-bukkit-2.6.21.jar")));
        assertFalse(Files.exists(volumes.resolve("smp/plugins").resolve(Applier.STAGING)));

        assertEquals(ApplyResult.Status.FAILED, outcome(result, "smp", "smp").status());
        assertEquals(
                ApplyResult.Status.FAILED, outcome(result, "smp", "voicechat").status());
        assertFalse(result.restartWorthOffering());
    }

    @Test
    void oneUnresolvableArtefactSkipsItsWholeServerJarsIncluded() throws IOException {
        install("smp", "plugins/smp-0.1.0.jar");

        final ApplyResult result = apply(
                new Fake(),
                plan(
                        outdated("smp", "smp", "smp-0.1.0.jar", "smp-0.2.0.jar"),
                        Change.unresolved("smp", "packetevents", "Modrinth: connect timed out")));

        // DisplayTags and PacketEvents are both required under smp; a partial swap here fails to start.
        assertTrue(Files.exists(volumes.resolve("smp/plugins/smp-0.1.0.jar")));
        assertFalse(Files.exists(volumes.resolve("smp/plugins/smp-0.2.0.jar")));
        assertEquals(ApplyResult.Status.SKIPPED, outcome(result, "smp", "smp").status());
        assertTrue(outcome(result, "smp", "smp").detail().contains("packetevents"));
        assertFalse(result.changedAnything());
    }

    @Test
    void anArtefactWithNoBuildForThisVersionDoesNotHoldItsServerBack() throws IOException {
        install("smp", "plugins/smp-0.1.0.jar");

        // CoreProtect has no build for this platform; a failure row would wrongly hold back the SMP's own jar too.
        final ApplyResult result = apply(
                new Fake(),
                plan(
                        outdated("smp", "smp", "smp-0.1.0.jar", "smp-0.2.0.jar"),
                        Change.unsupported("smp", "coreprotect", "no stable release for this platform")));

        assertTrue(Files.exists(volumes.resolve("smp/plugins/smp-0.2.0.jar")));
        assertEquals(ApplyResult.Status.DONE, outcome(result, "smp", "smp").status());

        // UNCHANGED claims a file that is there and SKIPPED refuses the whole service; neither fits a partial install.
        assertEquals(
                ApplyResult.Status.UNSUPPORTED,
                outcome(result, "smp", "coreprotect").status());
        assertFalse(result.skippedAnything());
        assertFalse(result.hasFailures());
        assertTrue(result.changedAnything());
    }

    @Test
    void aServerJarThatCouldNotBeResolvedDoesNotHoldThePluginsBack() throws IOException {
        install("smp", "plugins/smp-0.1.0.jar");
        install("smp", ".server/paper-26.2-121.jar");

        // The plugins are compiled against this version, not a build number, so a Fill outage protects nothing.
        final ApplyResult result = apply(
                new Fake(),
                plan(
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
    void oneServerFailingDoesNotStopAnother() throws IOException {
        install("smp", "plugins/smp-0.1.0.jar");
        install("limbo", "plugins/limbo-0.1.0.jar");

        final ApplyResult result = apply(
                new Fake().failingOn("smp-0.2.0.jar"),
                plan(
                        outdated("smp", "smp", "smp-0.1.0.jar", "smp-0.2.0.jar"),
                        outdated("limbo", "limbo", "limbo-0.1.0.jar", "limbo-0.2.0.jar")));

        assertEquals(ApplyResult.Status.FAILED, outcome(result, "smp", "smp").status());
        assertEquals(ApplyResult.Status.DONE, outcome(result, "limbo", "limbo").status());
        assertTrue(Files.exists(volumes.resolve("limbo/plugins/limbo-0.2.0.jar")));
    }

    @Test
    void thePacksTwoLinesAreWrittenFromTheReleaseHashIncluded() throws IOException {
        install("proxy", "plugins/proxy-0.1.0.jar");
        writePackYml();

        final Change pack = new Change(
                "proxy",
                "resource-pack",
                Change.Status.OUTDATED,
                "0000000000000000000000000000000000000000",
                new RemoteFile(
                        "resource-pack",
                        "0.2.0",
                        "nordtal-resource-pack-0.2.0.zip",
                        URI.create("https://github.com/nordtal/season-2/releases/download/v0.2.0/"
                                + "nordtal-resource-pack-0.2.0.zip"),
                        Checksum.sha1(SHA1)),
                null);

        final ApplyResult result = apply(new Fake(), plan(pack));

        final String written = Files.readString(PackState.fileIn(volumes.resolve("proxy")));
        assertTrue(written.contains("sha1: " + SHA1), written);
        assertTrue(written.contains("releases/download/v0.2.0/"), written);
        assertEquals(
                ApplyResult.Status.DONE,
                outcome(result, "proxy", "resource-pack").status());
        // The zip itself is never downloaded: the client fetches it, the proxy only describes it.
        assertFalse(Files.exists(volumes.resolve("proxy/plugins/nordtal-resource-pack-0.2.0.zip")));
    }

    @Test
    void theBotsJarGoesIntoTheRootOfItsOwnVolumeNotIntoAPluginsFolder() throws IOException {
        Files.createDirectories(volumes.resolve("discord-bot"));
        Files.writeString(volumes.resolve("discord-bot/discord-bot-0.1.0.jar"), "old");

        final ApplyResult result = apply(
                new Fake(),
                plan(new Change(
                        "discord-bot",
                        "discord-bot",
                        Change.Status.OUTDATED,
                        "discord-bot-0.1.0.jar",
                        remote("discord-bot", "discord-bot-0.2.0.jar"),
                        null)));

        assertEquals(
                ApplyResult.Status.DONE,
                outcome(result, "discord-bot", "discord-bot").status());
        assertTrue(Files.exists(volumes.resolve("discord-bot/discord-bot-0.2.0.jar")));
        assertFalse(
                Files.exists(volumes.resolve("discord-bot/discord-bot-0.1.0.jar")),
                "the superseded jar goes, by the same prefix rule as every plugin");
        assertFalse(
                Files.exists(volumes.resolve("discord-bot/plugins")),
                "there is no plugins folder here and none is created");
    }

    @Test
    void stewardWorkerInstallsItsOwnJarForTheNextStartToPickUp() throws IOException {
        Files.createDirectories(volumes.resolve("steward"));

        final ApplyResult result = apply(
                new Fake(),
                plan(new Change(
                        "steward",
                        "steward",
                        Change.Status.MISSING,
                        null,
                        remote("steward", "steward-0.2.0.jar"),
                        null)));

        assertEquals(
                ApplyResult.Status.DONE, outcome(result, "steward", "steward").status());
        assertTrue(
                Files.exists(volumes.resolve("steward/steward-0.2.0.jar")),
                "it lands in the volume; the process running right now carries on with the old one"
                        + " until the restart, which is the only way this module's version moves");
    }

    @Test
    void aVolumeThatIsNotMountedIsSkippedWholeNeverCreated() {
        final ApplyResult result = apply(
                new Fake(),
                plan(new Change(
                        "discord-bot",
                        "discord-bot",
                        Change.Status.MOUNT_MISSING,
                        null,
                        remote("discord-bot", "discord-bot-0.2.0.jar"),
                        "/volumes/discord-bot is not mounted in this container")));

        assertEquals(
                ApplyResult.Status.SKIPPED,
                outcome(result, "discord-bot", "discord-bot").status());
        assertFalse(result.changedAnything());
        assertFalse(Files.exists(volumes.resolve("discord-bot")));
    }

    @Test
    void aRunWhereEverythingWasSkippedDoesNotReadAsARunWhereNothingWasNeeded() {
        // Every volume unmounted and every row skipped must not close the report with "Nothing needed doing."
        final ApplyResult result = apply(
                new Fake(),
                plan(
                        Change.unresolved("smp", "packetevents", "Modrinth: connect timed out"),
                        new Change(
                                "smp",
                                "smp",
                                Change.Status.OUTDATED,
                                "smp-0.1.0.jar",
                                remote("smp", "smp-0.2.0.jar"),
                                null)));

        assertTrue(result.skippedAnything());
        assertFalse(result.changedAnything());
        assertFalse(result.hasFailures());

        final String rendered = eu.nordtal.s2.steward.run.Report.render(result);
        assertFalse(rendered.contains("Nothing needed doing"), rendered);
        assertTrue(rendered.contains("not because everything was current"), rendered);
    }

    @Test
    void b4ABootstrapWhoseSeasonJarIsUnresolvedInstallsNothingForThatServer() {
        // A resolved plugin next to an unresolved season jar must not let the entrypoint start on a partial install.
        final UpdatePlan bootstrap = plan(
                        Change.unresolved("smp", "smp", "could not read nordtal/season-2@latest: HTTP 403"),
                        new Change(
                                "smp",
                                "packetevents",
                                Change.Status.MISSING,
                                null,
                                remote("packetevents", "packetevents-spigot-2.13.0.jar"),
                                null),
                        new Change(
                                "smp",
                                "voicechat",
                                Change.Status.MISSING,
                                null,
                                remote("voicechat", "voicechat-bukkit-2.6.23.jar"),
                                null))
                .onlyMissing();

        final ApplyResult result = apply(new Fake(), bootstrap);

        assertFalse(
                Files.exists(volumes.resolve("smp/plugins/packetevents-spigot-2.13.0.jar")),
                "PacketEvents was installed beside a season that could not be resolved. That is the"
                        + " one shape of half-filled volume the empty-plugins guard cannot see.");
        assertFalse(Files.exists(volumes.resolve("smp/plugins/voicechat-bukkit-2.6.23.jar")));
        assertFalse(result.changedAnything());
        assertTrue(result.skippedAnything());

        final String rendered = eu.nordtal.s2.steward.run.Report.render(result);
        assertFalse(rendered.contains("Everything asked for was done"), rendered);
        assertTrue(rendered.contains("not because everything was current"), rendered);
    }

    @Test
    void m1APackThatCannotBeResolvedFallsBackAndDoesNotHoldTheJarsBack() throws IOException {
        install("proxy", "plugins/proxy-0.1.0.jar");

        // A release with no .sha1 beside the pack zip must not skip the whole service while the other backends update.
        final ApplyResult result = apply(
                new Fake(),
                plan(
                        Change.unresolved("proxy", Topology.RESOURCE_PACK, "the release published no .sha1 asset"),
                        outdated("proxy", "proxy", "proxy-0.1.0.jar", "proxy-0.2.0.jar")));

        assertTrue(
                Files.exists(volumes.resolve("proxy/plugins/proxy-0.2.0.jar")),
                "the proxy plugin was held back because the pack could not be checked");

        final ApplyResult.Outcome pack = outcome(result, "proxy", Topology.RESOURCE_PACK);
        assertEquals(
                ApplyResult.Status.SKIPPED,
                pack.status(),
                "a pack that could not be checked must not read as UNCHANGED - the client is still"
                        + " being sent the previous one, and that is a fallback, not a no-op");
        assertNotNull(pack.detail());
        assertTrue(pack.detail().contains("pack.yml was left alone"), pack.detail());
    }

    @Test
    void m1ThePackStillGetsARowWhenTheServiceReallyIsBlocked() {
        // A skipped service must still report on the one row a player can see: what the client is being sent.
        final ApplyResult result = apply(
                new Fake(),
                plan(
                        Change.unresolved("proxy", "proxy", "GitHub answered 403"),
                        new Change("proxy", Topology.RESOURCE_PACK, Change.Status.UP_TO_DATE, "abc123", null, null)));

        final ApplyResult.Outcome pack = outcome(result, "proxy", Topology.RESOURCE_PACK);
        assertNotNull(pack, "the pack row vanished from a report for a service that was skipped");
    }

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
        final StewardSpec config = new StewardSpec() {
            @Override
            public BunqSpec bunq() {
                // Defaults: empty credentials, which is "no bank account" and a valid season on its own.
                return new BunqSpec() {};
            }

            @Override
            public DockerSpec docker() {
                // Defaults: this test is not about the daemon, and nothing here reads it.
                return new DockerSpec() {};
            }

            @Override
            public String volumesRoot() {
                return volumes.toString();
            }

            @Override
            public UpdateSpec update() {
                return new UpdateSpec() {};
            }

            @Override
            public BackupSpec backup() {
                // Defaults throughout: this test is not about a backup, and BackupSpec's own defaults are fine.
                return new BackupSpec() {
                    // backup.remote has no default of its own, so an anonymous spec hands its defaults back by name.
                    @Override
                    public RemoteSpec remote() {
                        return new RemoteSpec() {};
                    }

                    @Override
                    public RetentionSpec retention() {
                        return new RetentionSpec() {};
                    }
                };
            }

            @Override
            public AgentSpec agent() {
                // Defaults: this test never recreates a container.
                return new AgentSpec() {};
            }
        };
        return new Applier(config, fetcher).apply(plan);
    }

    private static UpdatePlan plan(final Change... changes) {
        return new UpdatePlan(
                Instant.parse("2026-09-01T18:00:00Z"), "v0.2.0", false, List.of(changes), List.of(), List.of());
    }

    private static Change outdated(
            final String service, final String artifact, final String installed, final String wanted) {
        return new Change(service, artifact, Change.Status.OUTDATED, installed, remote(artifact, wanted), null);
    }

    private static RemoteFile remote(final String artifact, final String fileName) {
        return new RemoteFile(artifact, "x", fileName, URI.create("https://example.invalid/" + fileName), null);
    }

    private void install(final String service, final String relative) throws IOException {
        final Path file = volumes.resolve(service).resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "old", StandardCharsets.UTF_8);
    }

    private void writePackYml() throws IOException {
        final Path file = PackState.fileIn(volumes.resolve("proxy"));
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                enabled: true
                url: https://github.com/nordtal/season-2/releases/download/v0.1.0/nordtal-resource-pack-0.1.0.zip
                sha1: 0000000000000000000000000000000000000000
                force: true
                """, StandardCharsets.UTF_8);
    }

    /** Where the fetcher was asked to put one file, which is the staging directory. */
    private static Path parentOf(final Fake fetcher, final String fileName) {
        return fetcher.destinations.stream()
                .filter(candidate -> candidate.getFileName().toString().equals(fileName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(fileName + " was never fetched"))
                .getParent();
    }

    private static ApplyResult.Outcome outcome(final ApplyResult result, final String service, final String artifact) {
        return result.outcomes().stream()
                .filter(candidate -> candidate.artifact().equals(artifact))
                .filter(candidate ->
                        service == null ? candidate.service() == null : service.equals(candidate.service()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no outcome for " + service + "/" + artifact));
    }
}
