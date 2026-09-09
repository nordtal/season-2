package eu.nordtal.s2.updater.source;

import eu.nordtal.s2.updater.http.FakeHttp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Fill API, against the builds it published for 26.2 and 4.1.1 on 2026-09-01. */
class PaperFillTest {

    @Test
    @DisplayName("Paper: the newest STABLE build, with the API's own filename and its sha256")
    void newestStablePaper() throws IOException {
        final PaperFill fill = new PaperFill(
                new FakeHttp().serving("/projects/paper/versions/26.2/builds", "fill-paper-26.2.json"));

        final RemoteFile file = fill.newestStable("paper", "26.2");

        // paper-26.2-121.jar is the exact name deploy/minecraft/entrypoint.sh builds from three
        // variables. Reading it from the API keeps the two in step without either knowing about
        // the other - and it is what the installed-jar comparison is done on.
        assertEquals("paper-26.2-121.jar", file.fileName());
        assertEquals("121", file.version());
        assertNotNull(file.checksum());
        assertEquals("sha256", file.checksum().algorithm());
        assertTrue(file.url().toString().startsWith("https://fill-data.papermc.io/"), file.url().toString());
    }

    @Test
    @DisplayName("Velocity: the same shape, a different project")
    void newestStableVelocity() throws IOException {
        final PaperFill fill = new PaperFill(
                new FakeHttp().serving("/projects/velocity/versions/4.1.1/builds", "fill-velocity-4.1.1.json"));

        assertEquals("velocity-4.1.1-24.jar", fill.newestStable("velocity", "4.1.1").fileName());
    }

    @Test
    @DisplayName("a newer non-STABLE build is skipped, not taken because it is first")
    void skipsExperimentalBuilds() throws IOException {
        // Fill publishes ALPHA builds on the same endpoint, newest first. Taking builds[0] blindly
        // is how a whole network ends up on one without anybody choosing it.
        final String body = """
                [{"id":122,"channel":"ALPHA","time":"2026-08-30T00:00:00Z","downloads":{
                   "server:default":{"name":"paper-26.2-122.jar","url":"https://x/122",
                                     "checksums":{"sha256":"aa"}}}},
                 {"id":121,"channel":"STABLE","time":"2026-08-29T11:32:25Z","downloads":{
                   "server:default":{"name":"paper-26.2-121.jar","url":"https://x/121",
                                     "checksums":{"sha256":"bb"}}}}]
                """;
        final PaperFill fill = new PaperFill(new FakeHttp().answering("/builds", body));

        assertEquals("paper-26.2-121.jar", fill.newestStable("paper", "26.2").fileName());
    }

    @Test
    @DisplayName("a version with no stable build at all is an error naming the version")
    void refusesWhenNothingIsStable() {
        final PaperFill fill = new PaperFill(new FakeHttp().answering("/builds", "[]"));

        final IOException failure = assertThrows(IOException.class, () -> fill.newestStable("paper", "27.0"));
        assertTrue(failure.getMessage().contains("27.0"), failure.getMessage());
    }

    // ------------------------------------------------------------------ version families

    @Test
    @DisplayName("Velocity's family 4.0.0 resolves to 4.1.1, the four SNAPSHOTs in it ignored")
    void theFamilyResolvesToItsNewestRelease() throws IOException {
        // The real answer of GET /v3/projects/velocity, recorded 2026-09-09. `4.0.0` is Fill's name
        // for the whole 4.x line, so the family name is emphatically not a version anybody runs -
        // 4.0.0 is also a member of it, and the oldest one.
        final PaperFill fill = new PaperFill(
                new FakeHttp().serving("/projects/velocity", "fill-velocity-project.json"));

        assertEquals("4.1.1", fill.newestStableVersion("velocity", "4.0.0"));
    }

    @Test
    @DisplayName("a newer release in the same family wins")
    void aNewerReleaseWins() throws IOException {
        final PaperFill fill = new PaperFill(new FakeHttp().answering("/projects/velocity", """
                {"versions":{"4.0.0":["4.2.0-SNAPSHOT","4.2.0","4.1.1","4.1.0"]}}
                """));

        assertEquals("4.2.0", fill.newestStableVersion("velocity", "4.0.0"));
    }

    @Test
    @DisplayName("4.10.0 is newer than 4.9.0, which is the one thing sorting text gets wrong")
    void versionsAreComparedAsNumbers() throws IOException {
        // Lexicographically "4.10.0" < "4.9.0", so a text sort silently installs the older proxy -
        // and goes on doing it for as long as the minor stays two digits. Nothing about the run
        // fails, so the only symptom is a version number nobody looks at.
        final PaperFill fill = new PaperFill(new FakeHttp().answering("/projects/velocity", """
                {"versions":{"4.0.0":["4.9.0","4.10.0","4.8.3"]}}
                """));

        assertEquals("4.10.0", fill.newestStableVersion("velocity", "4.0.0"));
    }

    @Test
    @DisplayName("the position in the response decides nothing")
    void theOrderOfTheResponseIsNotTrusted() throws IOException {
        // Fill lists newest first today. That is an observation about one payload, and this is the
        // one place where believing it means quietly running an older proxy.
        final PaperFill fill = new PaperFill(new FakeHttp().answering("/projects/velocity", """
                {"versions":{"4.0.0":["4.0.0","4.1.0","4.1.1"]}}
                """));

        assertEquals("4.1.1", fill.newestStableVersion("velocity", "4.0.0"));
    }

    @Test
    @DisplayName("a family carrying only SNAPSHOTs fails, and never falls back to another family")
    void aFamilyOfSnapshotsIsAFailure() {
        // The failure that has to stay loud. A silent fallback onto 3.0.0 - which does carry
        // releases - would move the network to a different Velocity major, on a proxy compiled
        // against this one, without anybody asking for it.
        final PaperFill fill = new PaperFill(new FakeHttp().answering("/projects/velocity", """
                {"versions":{"4.0.0":["4.2.0-SNAPSHOT","4.1.2-SNAPSHOT"],"3.0.0":["3.5.1"]}}
                """));

        final IOException failure =
                assertThrows(IOException.class, () -> fill.newestStableVersion("velocity", "4.0.0"));
        assertTrue(failure.getMessage().contains("4.0.0"), failure.getMessage());
        assertTrue(failure.getMessage().contains("4.2.0-SNAPSHOT"),
                "the message has to show what it did find, or it names no way forward: "
                        + failure.getMessage());
        assertFalse(failure.getMessage().contains("3.5.1"), failure.getMessage());
    }

    @Test
    @DisplayName("an unknown family names the families that do exist")
    void anUnknownFamilyIsRefused() {
        final PaperFill fill = new PaperFill(new FakeHttp().answering("/projects/velocity", """
                {"versions":{"3.0.0":["3.5.1"]}}
                """));

        final IOException failure =
                assertThrows(IOException.class, () -> fill.newestStableVersion("velocity", "4.0.0"));
        assertTrue(failure.getMessage().contains("4.0.0"), failure.getMessage());
        assertTrue(failure.getMessage().contains("3.0.0"),
                "a family name is easy to get wrong precisely because it is not a version, so the"
                        + " message has to list the ones Fill knows: " + failure.getMessage());
    }

    @Test
    @DisplayName("a release candidate is not a release, in either project")
    void releaseCandidatesAreNotReleases() throws IOException {
        // Paper's own 26.2 family carries 26.2 and 26.2-rc-2, which is why the Paper side is an
        // exact version rather than a family - but the filter is the same one and is checked here.
        final PaperFill fill = new PaperFill(new FakeHttp().answering("/projects/paper", """
                {"versions":{"26.2":["26.2.1-pre1","26.2-rc-2","26.2"]}}
                """));

        assertEquals("26.2", fill.newestStableVersion("paper", "26.2"));
    }
}
