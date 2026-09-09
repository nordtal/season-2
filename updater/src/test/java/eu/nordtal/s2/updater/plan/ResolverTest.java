package eu.nordtal.s2.updater.plan;

import eu.nordtal.s2.common.Platform;
import eu.nordtal.s2.updater.config.UpdaterSpec;
import eu.nordtal.s2.updater.http.FakeHttp;
import eu.nordtal.s2.updater.http.HttpException;
import eu.nordtal.s2.updater.source.GitHubReleases;
import eu.nordtal.s2.updater.source.Modrinth;
import eu.nordtal.s2.updater.source.PaperFill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole of step 1, against recorded API responses and a volume tree on disk.
 * <p>
 * Every fixture is a real payload from 2026-09-01, and the release in it is the one that carried
 * the scaffold jars - so these tests run against exactly the situation the module was built to
 * make visible.
 * </p>
 */
class ResolverTest {

    /** The SHA-1 the release's .zip.sha1 asset is made to contain in these tests. */
    private static final String PACK_SHA1 = "6f1ed002ab5595859014ebf0951522d9d0f2ee34";

    @TempDir
    Path volumes;

    private FakeHttp http;

    @BeforeEach
    void wireEverySourceToItsRecordedResponse() {
        http = new FakeHttp()
                .serving("/repos/nordtal/season-2/releases", "github-season-v0.1.0.json")
                .serving("/repos/nordtal/papermc-display-tags/releases", "github-display-tags.json")
                .serving("/project/HYKaKraK/version", "modrinth-packetevents.json")
                .serving("/project/fALzjamp/version", "modrinth-chunky.json")
                .serving("/project/9eGKb6K1/version", "modrinth-voicechat.json")
                // The same project asked a second time for its Velocity build, so the route has to
                // be the loader filter rather than the project - a longer substring wins in
                // FakeHttp, and this is the only artefact resolved on that loader. The fixture is
                // the real answer: one version, alpha, which is the whole reason the pre-release
                // exception in Modrinth exists.
                .serving("loaders=%5B%22velocity%22%5D", "modrinth-voicechat-velocity.json")
                // A recorded EMPTY array - what Modrinth really answered for CoreProtect filtered
                // to 26.2/paper on 2026-09-08. It is a fixture and not a literal because the shape
                // of "no version matches" is the thing under test.
                .serving("/project/Lu3KuzdV/version", "modrinth-coreprotect-none.json")
                .serving("/projects/paper/versions/26.2/builds", "fill-paper-26.2.json")
                // Two calls for the proxy since 2026-09-09: the project list says which version
                // Velocity's major 4 is on, and only then is that version's build list read.
                .serving("/projects/velocity", "fill-velocity-project.json")
                .serving("/projects/velocity/versions/4.1.1/builds", "fill-velocity-4.1.1.json")
                .answering(".zip.sha1", PACK_SHA1 + "\n");
    }

    // ---------------------------------------------------------------- scenarios

    @Test
    @DisplayName("the proxy's version is resolved out of its family, and says nothing while it agrees")
    void theVelocityFamilyResolvesToTheCompiledApi() throws IOException {
        installCurrentEverything();

        final UpdatePlan plan = resolve();

        // 4.0.0 is Fill's name for the whole 4.x line and carries four SNAPSHOTs; what comes out is
        // 4.1.1, which is the version network-control is compiled against - the same number the
        // retired velocity-version pin held, reached without anybody maintaining it.
        assertEquals(Change.Status.UP_TO_DATE, statusOf(plan, "network-control", "velocity"));
        assertEquals(List.of(), plan.notes(),
                "the proxy resolved to the API it was built against, so there is nothing to warn"
                        + " about - a note here would be one an operator learns to ignore");
    }

    @Test
    @DisplayName("a Velocity newer than the API network-control was built for is named, not refused")
    void aVelocityAheadOfTheCatalogIsReported() throws IOException {
        installCurrentEverything();
        // The same family with one release added. This is the situation the whole note exists for:
        // nothing in the run fails, the proxy simply ends up on an API the plugin in it predates.
        http.answering("/projects/velocity", """
                {"project":{"id":"velocity","name":"Velocity"},
                 "versions":{"4.0.0":["4.2.0-SNAPSHOT","4.2.0","4.1.1","4.1.0","4.0.0"]}}
                """);
        http.answering("/projects/velocity/versions/4.2.0/builds", """
                [{"id":31,"channel":"STABLE","time":"2026-09-08T00:00:00Z","downloads":{
                   "server:default":{"name":"velocity-4.2.0-31.jar","url":"https://x/31",
                                     "checksums":{"sha256":"dd"}}}}]
                """);

        final UpdatePlan plan = resolve();

        // MISSING and not OUTDATED, and that is the filename identity rule showing through rather
        // than a defect: a jar is superseded by its prefix, and `velocity-4.1.1` and `velocity-4.2.0`
        // are different prefixes. It is the same thing a Paper version bump has always done. What it
        // costs is one line of the report - "velocity 4.2.0" instead of "4.1.1 -> 4.2.0" - and one
        // stale jar in .server/ that the entrypoint removes on the very start this run performs,
        // because it keeps exactly one jar per kind. Asserted rather than left to be discovered.
        assertEquals(Change.Status.MISSING, statusOf(plan, "network-control", "velocity"));
        assertTrue(plan.hasWork());
        assertEquals("velocity-4.2.0-31.jar",
                changeFor(plan, "network-control", "velocity").wanted().fileName());

        assertEquals(1, plan.notes().size(), "expected exactly one note: " + plan.notes());
        final String note = plan.notes().getFirst();
        assertTrue(note.contains("4.2.0") && note.contains(Platform.VELOCITY_API), note);
        assertTrue(PlanReport.of(plan).render().contains(note),
                "the note is decided by the resolver and drawn by PlanReport - a report that drops"
                        + " it is a version skew nobody is told about");
    }

    @Test
    @DisplayName("a deployment that is exactly what the sources say is up to date, with no work")
    void everythingCurrent() throws IOException {
        installCurrentEverything();

        final UpdatePlan plan = resolve();

        assertFalse(plan.hasWork(), Report.render(plan));
        assertEquals("v0.1.0", plan.seasonTag());
        assertEquals(Change.Status.UP_TO_DATE, statusOf(plan, "smp", "smp"));
        assertEquals(Change.Status.UP_TO_DATE, statusOf(plan, "smp", "packetevents"));
        assertEquals(Change.Status.UP_TO_DATE, statusOf(plan, "smp", "paper"));
        assertEquals(Change.Status.UP_TO_DATE, statusOf(plan, "network-control", "velocity"));
        assertEquals(Change.Status.UP_TO_DATE, statusOf(plan, "network-control", "resource-pack"));
    }

    @Test
    @DisplayName("voice chat is resolved for the two servers people play on, and for nothing else")
    void voiceChatIsOnTheTwoBackendsThatPlay() throws IOException {
        installCurrentEverything();

        final UpdatePlan plan = resolve();

        for (final String service : List.of("smp", "hunger-games")) {
            final Change change = changeFor(plan, service, "voicechat");
            assertEquals(Change.Status.UP_TO_DATE, change.status(), service);
            assertEquals("voicechat-bukkit-2.6.23.jar", change.installed(), service);
        }

        // limbo and the proxy carry no row for the SERVER half: an artefact nothing installs must
        // not appear as one that is merely up to date, because a row is what the guard and the
        // applier act on. The waiting room is seconds long and holds nobody who could be talked to,
        // and the proxy runs the other jar entirely.
        assertTrue(plan.changes().stream()
                        .filter(change -> "voicechat".equals(change.artifact()))
                        .noneMatch(change -> "limbo".equals(change.service())
                                || "network-control".equals(change.service())),
                Report.render(plan));
    }

    @Test
    @DisplayName("the proxy half of voice chat is resolved from a pre-release, and only on the proxy")
    void theVoiceChatProxyPluginIsOnTheProxyAlone() throws IOException {
        installCurrentEverything();

        final UpdatePlan plan = resolve();

        // Same Modrinth project as the row above, different loader and therefore a different jar.
        // This is the one artefact in the network installed from something not marked `release` -
        // Modrinth.PRE_RELEASE_EXCEPTIONS names it and says why - and this asserts the exception is
        // actually reached rather than merely declared: without it the row would be UNSUPPORTED and
        // the proxy would silently never get the plugin that makes one UDP port enough.
        final Change change = changeFor(plan, "network-control", "voicechat-velocity");
        assertEquals(Change.Status.UP_TO_DATE, change.status(), Report.render(plan));
        assertEquals("voicechat-velocity-2.6.18.jar", change.installed());

        assertTrue(plan.changes().stream()
                        .filter(row -> "voicechat-velocity".equals(row.artifact()))
                        .allMatch(row -> "network-control".equals(row.service())),
                "the Velocity build is on a Paper server: " + Report.render(plan));
    }

    @Test
    @DisplayName("a plugin with no build for this Minecraft version is UNSUPPORTED, not a failure")
    void aPluginWithNoBuildIsUnsupported() throws IOException {
        installCurrentEverything();

        final UpdatePlan plan = resolve();
        final Change change = changeFor(plan, "smp", "coreprotect");

        assertEquals(Change.Status.UNSUPPORTED, change.status());
        assertNull(change.wanted());
        assertNull(change.installed());
        assertNotNull(change.note());

        // The properties that make this different from UNRESOLVED, and every one of them is about
        // the SEASON JAR standing next to it rather than about CoreProtect. A failure row makes
        // Applier skip the whole service, so treating "the publisher has not shipped for 26.2" as
        // an outage would have meant the SMP's own jar was never installed - every run, for as long
        // as it lasted.
        assertFalse(change.status().isFailure(), Report.render(plan));
        assertFalse(change.status().isWork(), Report.render(plan));
        assertTrue(plan.changes().stream()
                        .filter(row -> "smp".equals(row.service()))
                        .noneMatch(row -> row.status().isFailure()),
                "one artefact with no build made the whole SMP untrustworthy: " + Report.render(plan));
    }

    @Test
    @DisplayName("a run that finds nothing but an unsupported artefact is nothing to do")
    void anUnsupportedOnlyRunIsNothingToDo() throws IOException {
        installCurrentEverything();

        final UpdatePlan plan = resolve();

        // Everything else on this deployment is current, so the only row that is not UP_TO_DATE is
        // CoreProtect's. That must not read as work: no server is stopped, nothing is installed,
        // and a report closing with "installed" would be a claim about a file that does not exist.
        assertFalse(plan.hasWork(), Report.render(plan));
        assertFalse(plan.hasMissing(), Report.render(plan));

        final eu.nordtal.s2.common.update.UpdateReport report = PlanReport.of(plan);
        assertFalse(report.isWork(), report.render());
        assertEquals(eu.nordtal.s2.common.update.UpdateReport.State.UNCHANGED,
                report.line("smp").state(), report.render());
        assertTrue(report.line("smp").changes().stream()
                        .anyMatch(entry -> entry.artefact().equals("coreprotect")
                                && entry.state() == eu.nordtal.s2.common.update.UpdateReport
                                        .Change.State.UNSUPPORTED),
                "the artefact has to stay NAMED while it waits - one dropped from the report is one"
                        + " somebody has to remember: " + report.render());

        // The text report names it too. What its SUMMARY says when nothing else is wrong is
        // ReportTest's job: this fixture's release predates the updater's own jar, so it always
        // carries one unresolved row and the summary is about that instead.
        final String text = Report.render(plan);
        assertTrue(text.contains("coreprotect"), text);
        assertTrue(text.contains("no build yet"), text);
    }

    @Test
    @DisplayName("a Modrinth outage is still a failure - it is the one that must not read as fine")
    void anOutageIsStillAFailure() throws IOException {
        installCurrentEverything();
        // The same artefact, the other reason for having no file. The distinction is the whole
        // point of the new status, so it is asserted from both sides.
        http.failing("/project/Lu3KuzdV/version", new HttpException(
                URI.create("https://api.modrinth.com/v2/project/Lu3KuzdV/version"), 503, "down"));

        final UpdatePlan plan = resolve();
        final Change change = changeFor(plan, "smp", "coreprotect");

        assertEquals(Change.Status.UNRESOLVED, change.status(), Report.render(plan));
        assertTrue(plan.hasFailures(), Report.render(plan));
    }

    @Test
    @DisplayName("an unclaimed voice chat jar on limbo is reported, never removed")
    void voiceChatOnLimboIsUnclaimed() throws IOException {
        installCurrentEverything();
        // Somebody dropped it in by hand. That is legitimate and has to stay visible: the updater
        // deletes nothing it does not account for, and a jar it silently ignored would be a second
        // copy of a plugin loading beside the one it does manage.
        write("limbo", "plugins/voicechat-bukkit-2.6.23.jar");

        final UpdatePlan plan = resolve();

        assertTrue(plan.unclaimed().stream().anyMatch(jar -> "limbo".equals(jar.service())
                        && jar.fileName().equals("voicechat-bukkit-2.6.23.jar")),
                Report.render(plan));
    }

    @Test
    @DisplayName("an older jar of the same plugin is OUTDATED, and the report names both files")
    void anOlderJarIsOutdated() throws IOException {
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
    @DisplayName("an empty but mounted volume is every row MISSING - a first deployment, not a fault")
    void aFreshVolumeIsAllMissing() throws IOException {
        for (final Topology.Service service : Topology.SERVICES) {
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
    @DisplayName("a volume that is not mounted is unknown, and unknown is not up to date")
    void anUnmountedVolumeIsNotAnEmptyOne() {
        // Nothing is created at all: this is a compose file that forgot a volume, and the
        // dangerous reading of it is "the SMP server has no plugins and needs all of them".
        final UpdatePlan plan = resolve();

        assertTrue(plan.hasFailures());
        assertEquals(Change.Status.MOUNT_MISSING, statusOf(plan, "smp", "smp"));
        assertTrue(Report.render(plan).contains("not the whole picture")
                        || Report.render(plan).contains("not the same as up to date"),
                Report.render(plan));
    }

    @Test
    @DisplayName("one source failing costs its own rows and nobody else's")
    void oneOutageStaysLocal() throws IOException {
        installCurrentEverything();
        http.failing("api.modrinth.com", new IOException("connect timed out"));

        final UpdatePlan plan = resolve();

        assertEquals(Change.Status.UNRESOLVED, statusOf(plan, "smp", "packetevents"));
        assertEquals(Change.Status.UNRESOLVED, statusOf(plan, "smp", "chunky"));
        // The question an operator is usually asking is about our own jars. Losing that answer to
        // somebody else's CDN would make this report worth less than the .env file it replaces.
        assertEquals(Change.Status.UP_TO_DATE, statusOf(plan, "smp", "smp"));
        assertEquals(Change.Status.UP_TO_DATE, statusOf(plan, "smp", "display-tags"));
        assertTrue(plan.hasFailures());
        assertFalse(plan.hasWork());
    }

    @Test
    @DisplayName("a season release that cannot be read names one reason on all six rows it feeds")
    void theSeasonReleaseIsTheExpensiveFailure() throws IOException {
        installCurrentEverything();
        http.failing("/repos/nordtal/season-2/", new HttpException(
                URI.create("https://api.github.com/repos/nordtal/season-2/releases/latest"), 404, "Not Found"));

        final UpdatePlan plan = resolve();

        assertEquals(Change.Status.UNRESOLVED, statusOf(plan, "smp", "smp"));
        assertEquals(Change.Status.UNRESOLVED, statusOf(plan, "network-control", "resource-pack"));
        assertEquals(Change.Status.UP_TO_DATE, statusOf(plan, "smp", "chunky"));
        // A 404 here is not an outage: it is a repository with no published release, which is a
        // thing a person has to go and do. The message has to say so.
        final Change change = changeFor(plan, "smp", "smp");
        assertNotNull(change.note());
        assertTrue(change.note().contains("not published") || change.note().contains("404"), change.note());
    }

    @Test
    @DisplayName("a jar nothing accounts for is reported and never touched")
    void unclaimedJarsAreReported() throws IOException {
        installCurrentEverything();
        write("smp", "plugins/SomeoneElsesPlugin-1.0.0.jar");

        final UpdatePlan plan = resolve();

        assertEquals(List.of(new UpdatePlan.Unclaimed("smp", "SomeoneElsesPlugin-1.0.0.jar")),
                plan.unclaimed());
        assertTrue(Report.render(plan).contains("left alone, never deleted"));
    }

    @Test
    @DisplayName("a plugin renamed by its publisher shows up as MISSING and unclaimed at once")
    void aRenamedJarIsVisibleFromBothSides() throws IOException {
        installCurrentEverything();
        // Chunky-Paper-* instead of Chunky-Bukkit-*. This is the one way this module could end up
        // installing a second copy of something, and the two rows together are what make it
        // obvious rather than mysterious.
        replace("smp", "plugins/Chunky-Bukkit-1.5.3.jar", "plugins/Chunky-Paper-1.5.3.jar");

        final UpdatePlan plan = resolve();

        assertEquals(Change.Status.MISSING, statusOf(plan, "smp", "chunky"));
        assertEquals(List.of(new UpdatePlan.Unclaimed("smp", "Chunky-Paper-1.5.3.jar")), plan.unclaimed());
    }

    @Test
    @DisplayName("the pack is compared on its hash, not on its URL")
    void packOutOfDate() throws IOException {
        installCurrentEverything();
        writePackYml("0000000000000000000000000000000000000000");

        final UpdatePlan plan = resolve();

        final Change change = changeFor(plan, "network-control", "resource-pack");
        assertEquals(Change.Status.OUTDATED, change.status());
        assertNotNull(change.wanted());
        assertNotNull(change.wanted().checksum());
        assertEquals(PACK_SHA1, change.wanted().checksum().hex());
        // The full hash on both sides: two pack releases sharing twelve leading hex
        // characters would otherwise render as an unexplained change from a value to itself.
        assertEquals("0000000000000000000000000000000000000000", change.installed());
        assertTrue(Report.render(plan).contains("sha1 " + PACK_SHA1), Report.render(plan));
    }

    @Test
    @DisplayName("no pack.yml yet is MISSING with the path in it, not a crash")
    void packNotConfiguredYet() throws IOException {
        installCurrentEverything();
        Files.delete(PackState.fileIn(volumes.resolve("network-control")));

        final Change change = changeFor(resolve(), "network-control", "resource-pack");

        assertEquals(Change.Status.MISSING, change.status());
        assertNotNull(change.note());
        assertTrue(change.note().contains("pack.yml"), change.note());
    }

    @Test
    @DisplayName("the bot is a jar in a volume like everything else, and reads as up to date")
    void theBotIsResolvedFromItsOwnVolume() throws IOException {
        installCurrentEverything();

        final Change change = changeFor(resolve(), "discord-bot", "discord-bot");

        assertEquals(Change.Status.UP_TO_DATE, change.status());
        assertEquals("discord-bot-0.1.0.jar", change.installed());
    }

    @Test
    @DisplayName("an older bot jar is work, exactly like an older plugin")
    void anOlderBotJarIsOutdated() throws IOException {
        installCurrentEverything();
        replace("discord-bot", "discord-bot-0.1.0.jar", "discord-bot-0.0.9.jar");

        final Change change = changeFor(resolve(), "discord-bot", "discord-bot");

        assertEquals(Change.Status.OUTDATED, change.status());
        assertEquals("discord-bot-0.0.9.jar", change.installed());
        assertTrue(change.status().isWork());
    }

    @Test
    @DisplayName("a volume that is not mounted is reported as that, never as an empty one")
    void anUnmountedStandaloneVolumeSaysSo() throws IOException {
        installCurrentEverything();
        Files.delete(volumes.resolve("discord-bot").resolve("discord-bot-0.1.0.jar"));
        Files.delete(volumes.resolve("discord-bot"));

        final Change change = changeFor(resolve(), "discord-bot", "discord-bot");

        assertEquals(Change.Status.MOUNT_MISSING, change.status());
        assertNotNull(change.note());
        assertTrue(change.note().contains("not mounted"), change.note());
    }

    @Test
    @DisplayName("a release with no updater jar in it leaves that row unresolved, not wrong")
    void aReleaseWithoutAnUpdaterJarSaysSo() throws IOException {
        installCurrentEverything();
        // v0.1.0 predates this module, so the release carries no updater jar - which is exactly
        // what an unresolvable row should look like, and it clears itself with the next release.
        final Change change = changeFor(resolve(), "updater", "updater");

        assertEquals(Change.Status.UNRESOLVED, change.status());
        assertNotNull(change.note());
        assertTrue(change.note().contains("updater-<version>.jar"), change.note());
    }

    @Test
    @DisplayName("the updater installs its own jar, which takes effect on the next start and not before")
    void theUpdaterResolvesItsOwnJar() throws IOException {
        installCurrentEverything();
        http.answering("/repos/nordtal/season-2/releases",
                FakeHttp.read("github-season-v0.1.0.json").replace("smp-0.1.0.jar", "updater-0.1.0.jar"));

        final Change change = changeFor(resolve(), "updater", "updater");

        // MISSING rather than UP_TO_DATE: the volume is mounted and empty, which is what a
        // deployment looks like before the first run that installs an updater jar into it.
        assertEquals(Change.Status.MISSING, change.status());
        assertTrue(change.status().isWork(),
                "it is work like anything else - what it is not is work that changes THIS process");
        assertNotNull(change.wanted());
        assertEquals("updater-0.1.0.jar", change.wanted().fileName());
    }

    @Test
    @DisplayName("a release pinned by tag that is a pre-release says so on the second line")
    void aPinnedPreReleaseIsAnnounced() throws IOException {
        installCurrentEverything();
        http.answering("/repos/nordtal/season-2/releases",
                FakeHttp.read("github-season-v0.1.0.json").replace("\"prerelease\": false", "\"prerelease\": true"));

        final UpdatePlan plan = resolve();

        assertTrue(plan.seasonPrerelease());
        assertTrue(Report.render(plan).contains("PRE-RELEASE"), Report.render(plan));
    }

    @Test
    @DisplayName("nothing on disk is written, read or created - not even the config directory")
    void resolvingWritesNothing() throws IOException {
        installCurrentEverything();
        final List<String> before = tree();

        resolve();

        assertEquals(before, tree());
    }

    // ---------------------------------------------------------------- fixtures on disk

    /** The exact deployment the recorded release and the recorded APIs describe. */
    private void installCurrentEverything() throws IOException {
        write("network-control", "plugins/network-control-0.1.0.jar");
        write("network-control", "plugins/voicechat-velocity-2.6.18.jar");
        write("network-control", ".server/velocity-4.1.1-24.jar");
        write("limbo", "plugins/limbo-0.1.0.jar");
        write("limbo", ".server/paper-26.2-121.jar");
        write("hunger-games", "plugins/hunger-games-0.1.0.jar");
        write("hunger-games", "plugins/voicechat-bukkit-2.6.23.jar");
        write("hunger-games", ".server/paper-26.2-121.jar");
        write("smp", "plugins/smp-0.1.0.jar");
        write("smp", "plugins/papermc-display-tags-2.0.0.jar");
        write("smp", "plugins/packetevents-spigot-2.13.0.jar");
        write("smp", "plugins/Chunky-Bukkit-1.5.3.jar");
        write("smp", "plugins/voicechat-bukkit-2.6.23.jar");
        write("smp", ".server/paper-26.2-121.jar");
        // The bot and the updater are one jar in the root of their own volume - no plugins folder,
        // nothing else in there. v0.1.0 carries no updater jar, so only the bot's is written here.
        write("discord-bot", "discord-bot-0.1.0.jar");
        Files.createDirectories(volumes.resolve("updater"));
        writePackYml(PACK_SHA1);
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

    private void writePackYml(final String sha1) throws IOException {
        final Path file = PackState.fileIn(volumes.resolve("network-control"));
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                enabled: true
                url: https://github.com/nordtal/season-2/releases/download/v0.1.0/nordtal-resource-pack-0.1.0.zip
                sha1: %s
                force: true
                apply-timeout-seconds: 180
                """.formatted(sha1), StandardCharsets.UTF_8);
    }

    private List<String> tree() throws IOException {
        try (var walk = Files.walk(volumes)) {
            return walk.map(volumes::relativize).map(Path::toString).sorted().toList();
        }
    }

    // ---------------------------------------------------------------- plumbing

    private UpdatePlan resolve() {
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
        return new Resolver(config, new GitHubReleases(http), new Modrinth(http), new PaperFill(http),
                Clock.fixed(Instant.parse("2026-09-01T18:00:00Z"), ZoneOffset.UTC)).resolve();
    }

    private static Change changeFor(final UpdatePlan plan, final String service, final String artifact) {
        final Optional<Change> change = plan.changes().stream()
                .filter(candidate -> artifact.equals(candidate.artifact()))
                .filter(candidate -> service == null
                        ? candidate.service() == null
                        : service.equals(candidate.service()))
                .findFirst();
        return change.orElseThrow(() -> new AssertionError(
                "no row for " + service + "/" + artifact + " in:\n" + Report.render(plan)));
    }

    private static Change.Status statusOf(final UpdatePlan plan, final String service, final String artifact) {
        return changeFor(plan, service, artifact).status();
    }
}
