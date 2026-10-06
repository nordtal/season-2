package eu.nordtal.season.stewardagent.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.Platform;
import eu.nordtal.season.common.http.HttpFailure;
import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.settings.MemorySettingStore;
import eu.nordtal.season.stewardagent.Told;
import eu.nordtal.season.stewardagent.config.RunSpec;
import eu.nordtal.season.stewardagent.config.RunSpec.BackupSpec;
import eu.nordtal.season.stewardagent.run.Report;
import eu.nordtal.season.stewardagent.source.Checksum;
import eu.nordtal.season.stewardagent.source.FakeHttp;
import eu.nordtal.season.stewardagent.source.GitHubReleases;
import eu.nordtal.season.stewardagent.source.Modrinth;
import eu.nordtal.season.stewardagent.source.PaperFill;
import eu.nordtal.season.stewardagent.topology.DeclaredTopology;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The whole of step 1, against recorded API responses and a volume tree on disk. */
class ResolverTest {

    /** The SHA-1 the release's .zip.sha1 asset is made to contain in these tests. */
    private static final String PACK_SHA1 = "6f1ed002ab5595859014ebf0951522d9d0f2ee34";

    @TempDir
    Path volumes;

    /** Where the proxy's pack is stored; {@code null} stands for a run without a database. */
    private @Nullable MemorySettingStore settings = new MemorySettingStore();

    private FakeHttp http;

    @BeforeEach
    void wireEverySourceToItsRecordedResponse() {
        http = new FakeHttp()
                .serving("/repos/nordtal/season-2/releases", "github-season-v0.1.0.json")
                .serving("/project/HYKaKraK/version", "modrinth-packetevents.json")
                .serving("/project/9eGKb6K1/version", "modrinth-voicechat.json")
                // Velocity is asked again by loader filter, not project; the longer substring wins in FakeHttp.
                .serving("loaders=%5B%22velocity%22%5D", "modrinth-voicechat-velocity.json")
                // A recorded EMPTY array is what Modrinth answers for CoreProtect on this platform: no version matches.
                .serving("/project/Lu3KuzdV/version", "modrinth-coreprotect-none.json")
                .serving("/projects/paper/versions/26.2/builds", "fill-paper-26.2.json")
                // Two calls for the proxy: the project list names Velocity's major, then its build list is read.
                .serving("/projects/velocity", "fill-velocity-project.json")
                .serving("/projects/velocity/versions/4.2.0/builds", "fill-velocity-4.2.0.json")
                .answering(".zip.sha1", PACK_SHA1 + "\n");
    }

    @Test
    void theProxysVersionIsResolvedOutOfItsFamilyAndSaysNothingWhileItAgrees() throws IOException {
        installCurrentEverything();

        final UpdatePlan plan = resolve();

        // 4.0.0 names the whole 4.x line; what resolves is 4.2.0, the version the proxy plugin is compiled against.
        assertEquals(Change.Status.UP_TO_DATE, statusOf(plan, "proxy", "velocity"));
        assertEquals(
                List.of(),
                plan.notes(),
                "the proxy resolved to the API it was built against, so there is nothing to warn"
                        + " about - a note here would be one an operator learns to ignore");
    }

    @Test
    void aVelocityNewerThanTheApiProxyWasBuiltForIsNamedNotRefused() throws IOException {
        installCurrentEverything();
        // Same family, one release added: the run must not fail, it should simply predate the API it lands on.
        http.answering("/projects/velocity", """
                {"project":{"id":"velocity","name":"Velocity"},
                 "versions":{"4.0.0":["4.3.0-SNAPSHOT","4.3.0","4.2.0","4.1.1","4.1.0","4.0.0"]}}
                """);
        http.answering("/projects/velocity/versions/4.3.0/builds", """
                [{"id":31,"channel":"STABLE","time":"2026-09-08T00:00:00Z","downloads":{
                   "server:default":{"name":"velocity-4.3.0-31.jar","url":"https://x/31",
                                     "checksums":{"sha256":"dd"}}}}]
                """);

        final UpdatePlan plan = resolve();

        // MISSING not OUTDATED: a different prefix is a different jar, exactly like a Paper version bump.
        assertEquals(Change.Status.MISSING, statusOf(plan, "proxy", "velocity"));
        assertTrue(plan.hasWork());
        assertEquals(
                "velocity-4.3.0-31.jar",
                changeFor(plan, "proxy", "velocity").wanted().fileName());

        assertEquals(1, plan.notes().size(), "expected exactly one note: " + plan.notes());
        // A key with typed values, so every surface words it in its reader's language and target.
        assertEquals("report.velocity-ahead", plan.notes().getFirst().key());
        final String note = Told.english(plan.notes().getFirst());
        assertTrue(note.contains("4.3.0") && note.contains(Platform.VELOCITY_API), note);
        assertTrue(
                Told.notes(PlanReport.of(plan)).contains(note),
                "the note is decided by the resolver and drawn by PlanReport - a report that drops"
                        + " it is a version skew nobody is told about");
    }

    @Test
    void aDeploymentThatIsExactlyWhatTheSourcesSayIsUpToDateWithNoWork() throws IOException {
        installCurrentEverything();

        final UpdatePlan plan = resolve();

        assertFalse(plan.hasWork(), Report.render(plan));
        assertEquals("v0.1.0", plan.seasonTag());
        assertEquals(Change.Status.UP_TO_DATE, statusOf(plan, "smp", "smp"));
        assertEquals(Change.Status.UP_TO_DATE, statusOf(plan, "smp", "packetevents"));
        assertEquals(Change.Status.UP_TO_DATE, statusOf(plan, "smp", "paper"));
        assertEquals(Change.Status.UP_TO_DATE, statusOf(plan, "proxy", "velocity"));
        assertEquals(Change.Status.UP_TO_DATE, statusOf(plan, "proxy", "resource-pack"));
    }

    @Test
    void voiceChatIsResolvedForTheTwoServersPeoplePlayOnAndForNothingElse() throws IOException {
        installCurrentEverything();

        final UpdatePlan plan = resolve();

        for (final String service : List.of("smp", "hunger-games")) {
            final Change change = changeFor(plan, service, "voicechat");
            assertEquals(Change.Status.UP_TO_DATE, change.status(), service);
            assertEquals("voicechat-bukkit-2.6.23.jar", change.installed(), service);
        }

        // limbo and the proxy carry no SERVER row for voicechat: nothing installs it, so it must not read as current.
        assertTrue(
                plan.changes().stream()
                        .filter(change -> "voicechat".equals(change.artifact()))
                        .noneMatch(change -> "limbo".equals(change.service()) || "proxy".equals(change.service())),
                Report.render(plan));
    }

    @Test
    void theProxyHalfOfVoiceChatIsResolvedFromAPreReleaseAndOnlyOnTheProxy() throws IOException {
        installCurrentEverything();

        final UpdatePlan plan = resolve();

        // A different loader and jar, from Modrinth.PRE_RELEASE_EXCEPTIONS: asserts the exception is reached.
        final Change change = changeFor(plan, "proxy", "voicechat-velocity");
        assertEquals(Change.Status.UP_TO_DATE, change.status(), Report.render(plan));
        assertEquals("voicechat-velocity-2.6.18.jar", change.installed());

        assertTrue(
                plan.changes().stream()
                        .filter(row -> "voicechat-velocity".equals(row.artifact()))
                        .allMatch(row -> "proxy".equals(row.service())),
                "the Velocity build is on a Paper server: " + Report.render(plan));
    }

    @Test
    void aPluginWithNoBuildForThisMinecraftVersionIsUnsupportedNotAFailure() throws IOException {
        installCurrentEverything();

        final UpdatePlan plan = resolve();
        final Change change = changeFor(plan, "smp", "coreprotect");

        assertEquals(Change.Status.UNSUPPORTED, change.status());
        assertNull(change.wanted());
        assertNull(change.installed());
        assertNotNull(change.reason());

        // Distinct from UNRESOLVED: a failure row skips the whole service, so a missing build must not read as one.
        assertFalse(change.status().isFailure(), Report.render(plan));
        assertFalse(change.status().isWork(), Report.render(plan));
        assertTrue(
                plan.changes().stream()
                        .filter(row -> "smp".equals(row.service()))
                        .noneMatch(row -> row.status().isFailure()),
                "one artefact with no build made the whole SMP untrustworthy: " + Report.render(plan));
    }

    @Test
    void aRunThatFindsNothingButAnUnsupportedArtefactIsNothingToDo() throws IOException {
        installCurrentEverything();

        final UpdatePlan plan = resolve();

        // Everything else is current, so CoreProtect alone must not turn the report into a claim of work done.
        assertFalse(plan.hasWork(), Report.render(plan));
        assertFalse(plan.hasMissing(), Report.render(plan));

        final eu.nordtal.season.database.update.UpdateReport report = PlanReport.of(plan);
        assertFalse(report.isWork(), report.toString());
        assertEquals(
                eu.nordtal.season.database.update.UpdateReport.State.UNCHANGED,
                report.line("smp").state(),
                report.toString());
        assertTrue(
                report.line("smp").changes().stream()
                        .anyMatch(entry -> entry.artefact().equals("coreprotect")
                                && entry.state()
                                        == eu.nordtal.season.database.update.UpdateReport.Change.State.UNSUPPORTED),
                "the artefact has to stay NAMED while it waits - one dropped from the report is one"
                        + " somebody has to remember: " + report);

        // The text report names it too; its SUMMARY when nothing else is wrong is ReportTest's job, not this one's.
        final String text = Report.render(plan);
        assertTrue(text.contains("coreprotect"), text);
        assertTrue(text.contains("no build yet"), text);
    }

    @Test
    void aModrinthOutageIsStillAFailureItIsTheOneThatMustNotReadAsFine() throws IOException {
        installCurrentEverything();
        // Same artefact, the other reason for no file: asserted from both sides of the new status.
        http.failing(
                "/project/Lu3KuzdV/version",
                new HttpFailure(URI.create("https://api.modrinth.com/v2/project/Lu3KuzdV/version"), 503, "down"));

        final UpdatePlan plan = resolve();
        final Change change = changeFor(plan, "smp", "coreprotect");

        assertEquals(Change.Status.UNRESOLVED, change.status(), Report.render(plan));
        assertTrue(plan.hasFailures(), Report.render(plan));
    }

    @Test
    void anUnclaimedVoiceChatJarOnLimboIsReportedNeverRemoved() throws IOException {
        installCurrentEverything();
        // A hand-placed jar stays visible: steward deletes nothing it does not account for.
        write("limbo", "plugins/voicechat-bukkit-2.6.23.jar");

        final UpdatePlan plan = resolve();

        assertTrue(
                plan.unclaimed().stream()
                        .anyMatch(jar ->
                                "limbo".equals(jar.service()) && jar.fileName().equals("voicechat-bukkit-2.6.23.jar")),
                Report.render(plan));
    }

    @Test
    void anOlderJarOfTheSamePluginIsOutdatedAndTheReportNamesBothFiles() throws IOException {
        installCurrentEverything();
        replace("smp", "plugins/smp-0.1.0.jar", "plugins/smp-0.0.9.jar");

        final UpdatePlan plan = resolve();

        assertTrue(plan.hasWork());
        final Change change = changeFor(plan, "smp", "smp");
        assertEquals(Change.Status.OUTDATED, change.status());
        assertEquals("smp-0.0.9.jar", change.installed());
        assertNotNull(change.wanted());
        assertEquals("smp-0.1.0.jar", change.wanted().fileName());
        assertTrue(Report.render(plan).contains("smp-0.0.9.jar  ->  smp-0.1.0.jar"), Report.render(plan));
    }

    @Test
    void aSeasonJarCarriesTheSha256DigestGitHubPublishedForIt() throws IOException {
        installCurrentEverything();
        replace("smp", "plugins/smp-0.1.0.jar", "plugins/smp-0.0.9.jar");

        final Change change = changeFor(resolve(), "smp", "smp");

        assertNotNull(change.wanted());
        assertEquals(
                Checksum.sha256("0f1ab0e5be10515e0e25a97f9818bba46b3e8a5f269e1534129c20b698ab1937"),
                change.wanted().checksum());
    }

    @Test
    void aSeasonJarWithoutADigestIsRefusedAndTheInstalledOneStays() throws IOException {
        installCurrentEverything();
        replace("smp", "plugins/smp-0.1.0.jar", "plugins/smp-0.0.9.jar");
        http.answering(
                "/repos/nordtal/season-2/releases",
                FakeHttp.read("github-season-v0.1.0.json")
                        .replace(
                                "\"digest\": \"sha256:0f1ab0e5be10515e0e25a97f9818bba46b3e8a5f269e1534129c20b698ab1937\",",
                                ""));

        final UpdatePlan plan = resolve();
        final Change smp = changeFor(plan, "smp", "smp");

        assertEquals(Change.Status.UNRESOLVED, smp.status(), Report.render(plan));
        assertTrue(smp.status().isFailure(), Report.render(plan));
        assertNull(smp.wanted());
        assertNotNull(smp.reason());
        assertTrue(
                Told.english(smp.reason()).contains("smp-0.1.0.jar without a sha256 digest"),
                Told.english(smp.reason()));
        assertEquals(Change.Status.UP_TO_DATE, statusOf(plan, "limbo", "limbo"), Report.render(plan));
    }

    @Test
    void anEmptyButMountedVolumeIsEveryRowMissingAFirstDeploymentNotAFault() throws IOException {
        for (final Topology.Service service : DeclaredTopology.topology().servers()) {
            Files.createDirectories(volumes.resolve(service.name()).resolve("plugins"));
        }

        final UpdatePlan plan = resolve();

        assertTrue(plan.hasWork());
        assertEquals(Change.Status.MISSING, statusOf(plan, "smp", "smp"));
        assertEquals(Change.Status.MISSING, statusOf(plan, "limbo", "paper"));
        // MISSING is work, never a failure: the answer "install all of it" is a complete answer.
        assertFalse(plan.changes().stream()
                .filter(change -> "limbo".equals(change.service()))
                .anyMatch(change -> change.status().isFailure()));
    }

    @Test
    void aVolumeThatIsNotMountedIsUnknownAndUnknownIsNotUpToDate() {
        // Nothing is created: an unmounted volume must not read as "the SMP has no plugins and needs all of them".
        final UpdatePlan plan = resolve();

        assertTrue(plan.hasFailures());
        assertEquals(Change.Status.MOUNT_MISSING, statusOf(plan, "smp", "smp"));
        assertTrue(
                Report.render(plan).contains("not the whole picture")
                        || Report.render(plan).contains("not the same as up to date"),
                Report.render(plan));
    }

    @Test
    void oneSourceFailingCostsItsOwnRowsAndNobodyElses() throws IOException {
        installCurrentEverything();
        http.failing("api.modrinth.com", new IOException("connect timed out"));

        final UpdatePlan plan = resolve();

        assertEquals(Change.Status.UNRESOLVED, statusOf(plan, "smp", "packetevents"));
        assertEquals(Change.Status.UNRESOLVED, statusOf(plan, "smp", "voicechat"));
        // An operator asks about our own jars; losing that to a third-party CDN outage makes the report worth less.
        assertEquals(Change.Status.UP_TO_DATE, statusOf(plan, "smp", "smp"));
        assertTrue(plan.hasFailures());
        assertFalse(plan.hasWork());
    }

    @Test
    void aSeasonReleaseThatCannotBeReadNamesOneReasonOnAllSixRowsItFeeds() throws IOException {
        installCurrentEverything();
        http.failing(
                "/repos/nordtal/season-2/",
                new HttpFailure(
                        URI.create("https://api.github.com/repos/nordtal/season-2/releases/latest"), 404, "Not Found"));

        final UpdatePlan plan = resolve();

        assertEquals(Change.Status.UNRESOLVED, statusOf(plan, "smp", "smp"));
        assertEquals(Change.Status.UNRESOLVED, statusOf(plan, "proxy", "resource-pack"));
        assertEquals(Change.Status.UP_TO_DATE, statusOf(plan, "smp", "packetevents"));
        // A 404 is not an outage: it is a repository with no published release, and the message has to say so.
        final Change change = changeFor(plan, "smp", "smp");
        assertNotNull(change.reason());
        assertTrue(
                Told.english(change.reason()).contains("not published")
                        || Told.english(change.reason()).contains("404"),
                Told.english(change.reason()));
    }

    @Test
    void aJarNothingAccountsForIsReportedAndNeverTouched() throws IOException {
        installCurrentEverything();
        write("smp", "plugins/SomeoneElsesPlugin-1.0.0.jar");

        final UpdatePlan plan = resolve();

        assertEquals(List.of(new UpdatePlan.Unclaimed("smp", "SomeoneElsesPlugin-1.0.0.jar")), plan.unclaimed());
        assertTrue(Report.render(plan).contains("left alone, never deleted"));
    }

    @Test
    void aPluginRenamedByItsPublisherShowsUpAsMissingAndUnclaimedAtOnce() throws IOException {
        installCurrentEverything();
        // packetevents-paper-* instead of -spigot-*: the two rows together make a renamed jar obvious.
        replace("smp", "plugins/packetevents-spigot-2.13.0.jar", "plugins/packetevents-paper-2.13.0.jar");

        final UpdatePlan plan = resolve();

        assertEquals(Change.Status.MISSING, statusOf(plan, "smp", "packetevents"));
        assertEquals(List.of(new UpdatePlan.Unclaimed("smp", "packetevents-paper-2.13.0.jar")), plan.unclaimed());
    }

    @Test
    void aJarNamedWithATrailingWordIsStillTheOneThatTheNewerBuildReplaces() throws IOException {
        installCurrentEverything();
        replace("smp", "plugins/packetevents-spigot-2.13.0.jar", "plugins/packetevents-spigot-2.12.0-paper.jar");

        final UpdatePlan plan = resolve();

        final Change change = changeFor(plan, "smp", "packetevents");
        assertEquals(Change.Status.OUTDATED, change.status(), Report.render(plan));
        assertEquals("packetevents-spigot-2.12.0-paper.jar", change.installed());
        assertEquals(List.of(), plan.unclaimed(), "the old copy is the jar the row replaces, not a stranger");
    }

    @Test
    void thePackIsComparedOnItsHashNotOnItsUrl() throws IOException {
        installCurrentEverything();
        storePack("0000000000000000000000000000000000000000");

        final UpdatePlan plan = resolve();

        final Change change = changeFor(plan, "proxy", "resource-pack");
        assertEquals(Change.Status.OUTDATED, change.status());
        assertNotNull(change.wanted());
        assertNotNull(change.wanted().checksum());
        assertEquals(PACK_SHA1, change.wanted().checksum().hex());
        // Full hash on both sides: releases sharing a hex prefix must not render as a change from a value to itself.
        assertEquals("0000000000000000000000000000000000000000", change.installed());
        assertTrue(Report.render(plan).contains("sha1 " + PACK_SHA1), Report.render(plan));
    }

    @Test
    void aProxyWithNoPackYetIsMissingNotACrash() throws IOException {
        installCurrentEverything();
        settings = new MemorySettingStore();

        final Change change = changeFor(resolve(), "proxy", "resource-pack");

        assertEquals(Change.Status.MISSING, change.status());
        assertNotNull(change.reason());
        assertEquals("report.proxy-without-pack", change.reason().key());
        assertTrue(Told.english(change.reason()).contains("no pack yet"), Told.english(change.reason()));
    }

    @Test
    void withoutADatabaseThePackIsUnknownNotMissing() throws IOException {
        installCurrentEverything();
        settings = null;

        final Change change = changeFor(resolve(), "proxy", "resource-pack");

        // MISSING would make a bootstrap install it, which needs the very database that is not there.
        assertEquals(Change.Status.UNRESOLVED, change.status());
        assertNotNull(change.reason());
        assertTrue(Told.english(change.reason()).contains("no database"), Told.english(change.reason()));
    }

    @Test
    void aReleaseWithoutAPackZipKeepsTheInstalledPackLikeASeasonJar() throws IOException {
        installCurrentEverything();
        // Renamed so that neither asset ends in .zip or .zip.sha1 any more: the release carries no pack.
        http.answering(
                "/repos/nordtal/season-2/releases",
                FakeHttp.read("github-season-v0.1.0.json")
                        .replace("nordtal-resource-pack-0.1.0.zip", "nordtal-resource-pack-0.1.0.txt"));

        final UpdatePlan plan = resolve();
        final Change pack = changeFor(plan, "proxy", "resource-pack");

        assertEquals(Change.Status.NOT_IN_RELEASE, pack.status(), Report.render(plan));
        assertEquals(PACK_SHA1, pack.installed());
        assertFalse(pack.status().isFailure(), Report.render(plan));
        final eu.nordtal.season.database.update.UpdateReport report = PlanReport.of(plan);
        assertTrue(
                Told.notes(report).stream().anyMatch(note -> note.contains(PACK_SHA1 + " stays")),
                "the missing pack is a note in the report: " + report);
    }

    @Test
    void aReleaseWithoutAPackZipAndNoPackInstalledIsStillUnresolved() throws IOException {
        installCurrentEverything();
        settings = new MemorySettingStore();
        http.answering(
                "/repos/nordtal/season-2/releases",
                FakeHttp.read("github-season-v0.1.0.json")
                        .replace("nordtal-resource-pack-0.1.0.zip", "nordtal-resource-pack-0.1.0.txt"));

        final Change pack = changeFor(resolve(), "proxy", "resource-pack");

        assertEquals(Change.Status.UNRESOLVED, pack.status());
        assertNotNull(pack.reason());
        assertTrue(Told.english(pack.reason()).contains("carries no pack zip"), Told.english(pack.reason()));
    }

    @Test
    void aVolumeThatIsNotMountedIsReportedAsThatNeverAsAnEmptyOne() throws IOException {
        installCurrentEverything();
        try (var walk = Files.walk(volumes.resolve("limbo"))) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> path.toFile().delete());
        }

        final Change change = changeFor(resolve(), "limbo", "limbo");

        assertEquals(Change.Status.MOUNT_MISSING, change.status());
        assertNotNull(change.reason());
        assertTrue(Told.english(change.reason()).contains("not mounted"), Told.english(change.reason()));
    }

    @Test
    void aReleaseWithoutTheSmpJarLeavesTheInstalledOneAloneAndMovesThePluginsBesideIt() throws IOException {
        installCurrentEverything();
        replace("smp", "plugins/packetevents-spigot-2.13.0.jar", "plugins/packetevents-spigot-2.12.0.jar");
        http.answering(
                "/repos/nordtal/season-2/releases",
                FakeHttp.read("github-season-v0.1.0.json").replace("smp-0.1.0.jar", "other-0.1.0.jar"));

        final UpdatePlan plan = resolve();
        final Change smp = changeFor(plan, "smp", "smp");

        // The release answered but has no file for this jar: NOT_IN_RELEASE, not a failed lookup.
        assertEquals(Change.Status.NOT_IN_RELEASE, smp.status(), Report.render(plan));
        assertEquals("smp-0.1.0.jar", smp.installed());
        assertFalse(smp.status().isFailure(), Report.render(plan));
        assertFalse(smp.status().isWork(), Report.render(plan));
        assertEquals(
                Change.Status.OUTDATED, changeFor(plan, "smp", "packetevents").status());

        final eu.nordtal.season.database.update.UpdateReport report = PlanReport.of(plan);
        assertEquals(
                eu.nordtal.season.database.update.UpdateReport.State.PLANNED,
                report.line("smp").state(),
                report.toString());
        assertTrue(
                Told.notes(report).stream().anyMatch(note -> note.contains("smp-0.1.0.jar stays")),
                "the missing jar is a warning in the report: " + report);
    }

    @Test
    void aReleaseWithoutTheSmpJarAndNothingElseNewIsNothingToDo() throws IOException {
        installCurrentEverything();
        http.answering(
                "/repos/nordtal/season-2/releases",
                FakeHttp.read("github-season-v0.1.0.json").replace("smp-0.1.0.jar", "other-0.1.0.jar"));

        final eu.nordtal.season.database.update.UpdateReport report = PlanReport.of(resolve());

        assertFalse(report.line("smp").isMoving(), report.toString());
        assertEquals(
                eu.nordtal.season.database.update.UpdateReport.State.UNCHANGED,
                report.line("smp").state(),
                report.toString());
    }

    @Test
    void aServiceHeldBackByARealFailureIsNotMovingSoNobodyIsCountedDownForIt() throws IOException {
        installCurrentEverything();
        replace("smp", "plugins/packetevents-spigot-2.13.0.jar", "plugins/packetevents-spigot-2.12.0.jar");
        http.failing(
                "/project/Lu3KuzdV/version",
                new HttpFailure(URI.create("https://api.modrinth.com/v2/project/Lu3KuzdV/version"), 503, "down"));

        final eu.nordtal.season.database.update.UpdateReport report = PlanReport.of(resolve());

        // Applier skips the whole SMP over the outage; the line stays FAILED and says what it held back.
        assertEquals(
                eu.nordtal.season.database.update.UpdateReport.State.FAILED,
                report.line("smp").state(),
                report.toString());
        assertFalse(report.line("smp").isMoving(), report.toString());
        assertTrue(Told.detail(report.line("smp")).contains("packetevents"), report.toString());
    }

    @Test
    void aServerJarThatCouldNotBeCheckedHoldsNothingBackSoThePluginsStillMove() throws IOException {
        installCurrentEverything();
        replace("smp", "plugins/packetevents-spigot-2.13.0.jar", "plugins/packetevents-spigot-2.12.0.jar");
        http.failing(
                "/projects/paper/versions/26.2/builds",
                new HttpFailure(
                        URI.create("https://fill.papermc.io/v3/projects/paper/versions/26.2/builds"), 503, "down"));

        final eu.nordtal.season.database.update.UpdateReport report = PlanReport.of(resolve());

        // The applier installs plugins beside a paper row it could not check, so the line must say it moves.
        assertTrue(report.line("smp").isMoving(), report.toString());
    }

    @Test
    void aReleasePinnedByTagThatIsAPreReleaseSaysSoOnTheSecondLine() throws IOException {
        installCurrentEverything();
        http.answering(
                "/repos/nordtal/season-2/releases",
                FakeHttp.read("github-season-v0.1.0.json").replace("\"prerelease\": false", "\"prerelease\": true"));

        final UpdatePlan plan = resolve();

        assertTrue(plan.seasonPrerelease());
        assertTrue(Report.render(plan).contains("PRE-RELEASE"), Report.render(plan));
    }

    @Test
    void nothingOnDiskIsWrittenReadOrCreatedNotEvenTheConfigDirectory() throws IOException {
        installCurrentEverything();
        final List<String> before = tree();

        resolve();

        assertEquals(before, tree());
    }

    /** The exact deployment the recorded release and the recorded APIs describe. */
    private void installCurrentEverything() throws IOException {
        write("proxy", "plugins/proxy-0.1.0.jar");
        write("proxy", "plugins/voicechat-velocity-2.6.18.jar");
        write("proxy", ".server/velocity-4.2.0-30.jar");
        write("limbo", "plugins/limbo-0.1.0.jar");
        write("limbo", ".server/paper-26.2-121.jar");
        write("hunger-games", "plugins/hunger-games-0.1.0.jar");
        write("hunger-games", "plugins/voicechat-bukkit-2.6.23.jar");
        write("hunger-games", ".server/paper-26.2-121.jar");
        write("smp", "plugins/smp-0.1.0.jar");
        write("smp", "plugins/packetevents-spigot-2.13.0.jar");
        write("smp", "plugins/voicechat-bukkit-2.6.23.jar");
        write("smp", ".server/paper-26.2-121.jar");
        storePack(PACK_SHA1);
    }

    private void write(final String service, final String relative) throws IOException {
        final Path file = volumes.resolve(service).resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "not really a jar", StandardCharsets.UTF_8);
    }

    private void replace(final String service, final String from, final String to) throws IOException {
        Files.delete(volumes.resolve(service).resolve(from));
        write(service, to);
    }

    private void storePack(final String sha1) {
        settings.set(
                        "proxy",
                        "pack",
                        "url",
                        "https://github.com/nordtal/season-2/releases/download/v0.1.0/nordtal-resource-pack-0.1.0.zip")
                .set("proxy", "pack", "sha1", sha1);
    }

    private List<String> tree() throws IOException {
        try (var walk = Files.walk(volumes)) {
            return walk.map(volumes::relativize).map(Path::toString).sorted().toList();
        }
    }

    private UpdatePlan resolve() {
        return new Resolver(
                        testConfig(),
                        new GitHubReleases(http),
                        new Modrinth(http),
                        new PaperFill(http),
                        Clock.fixed(Instant.parse("2026-09-01T18:00:00Z"), ZoneOffset.UTC),
                        DeclaredTopology.topology().servers(),
                        eu.nordtal.season.stewardagent.plugin.PluginDirectory.NONE,
                        settings)
                .resolve();
    }

    /** A {@link RunSpec} whose every section but {@link RunSpec#volumesRoot} is a plain default. */
    private RunSpec testConfig() {
        return new RunSpec() {

            @Override
            public String volumesRoot() {
                return volumes.toString();
            }

            @Override
            public BackupSpec backup() {
                // Defaults throughout: this test is not about a backup, and BackupSpec's own defaults are fine.
                return new BackupSpec() {
                    @Override
                    public RetentionSpec retention() {
                        return new RetentionSpec() {};
                    }
                };
            }
        };
    }

    private static Change changeFor(final UpdatePlan plan, final String service, final String artifact) {
        final Optional<Change> change = plan.changes().stream()
                .filter(candidate -> artifact.equals(candidate.artifact()))
                .filter(candidate ->
                        service == null ? candidate.service() == null : service.equals(candidate.service()))
                .findFirst();
        return change.orElseThrow(
                () -> new AssertionError("no row for " + service + "/" + artifact + " in:\n" + Report.render(plan)));
    }

    private static Change.Status statusOf(final UpdatePlan plan, final String service, final String artifact) {
        return changeFor(plan, service, artifact).status();
    }
}
