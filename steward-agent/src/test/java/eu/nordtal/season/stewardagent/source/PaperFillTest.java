package eu.nordtal.season.stewardagent.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/** The Fill API, against the builds it publishes for 26.2 and the 4.1.1 Velocity family. */
class PaperFillTest {

    @Test
    void paperTheNewestStableBuildWithTheApisOwnFilenameAndItsSha256() throws IOException {
        final PaperFill fill =
                new PaperFill(new FakeHttp().serving("/projects/paper/versions/26.2/builds", "fill-paper-26.2.json"));

        final RemoteFile file = fill.newestStable("paper", "26.2");

        // paper-26.2-121.jar is the exact name entrypoint.sh builds from three variables, keeping installs in step.
        assertEquals("paper-26.2-121.jar", file.fileName());
        assertEquals("121", file.version());
        assertNotNull(file.checksum());
        assertEquals("sha256", file.checksum().algorithm());
        assertTrue(
                file.url().toString().startsWith("https://fill-data.papermc.io/"),
                file.url().toString());
    }

    @Test
    void velocityTheSameShapeADifferentProject() throws IOException {
        final PaperFill fill = new PaperFill(
                new FakeHttp().serving("/projects/velocity/versions/4.1.1/builds", "fill-velocity-4.1.1.json"));

        assertEquals(
                "velocity-4.1.1-24.jar", fill.newestStable("velocity", "4.1.1").fileName());
    }

    @Test
    void aNewerNonStableBuildIsSkippedNotTakenBecauseItIsFirst() throws IOException {
        // Fill publishes ALPHA builds on the same endpoint, newest first; taking builds[0] blindly risks an alpha.
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
    void aVersionWithNoStableBuildAtAllIsAnErrorNamingTheVersion() {
        final PaperFill fill = new PaperFill(new FakeHttp().answering("/builds", "[]"));

        final IOException failure = assertThrows(IOException.class, () -> fill.newestStable("paper", "27.0"));
        assertTrue(failure.getMessage().contains("27.0"), failure.getMessage());
    }

    @Test
    void velocitysFamily400ResolvesTo420TheFourSnapshotsInItIgnored() throws IOException {
        // `4.0.0` is Fill's name for the whole 4.x line, so the family name is not a version anybody runs.
        final PaperFill fill =
                new PaperFill(new FakeHttp().serving("/projects/velocity", "fill-velocity-project.json"));

        assertEquals("4.2.0", fill.newestStableVersion("velocity", "4.0.0"));
    }

    @Test
    void aNewerReleaseInTheSameFamilyWins() throws IOException {
        final PaperFill fill = new PaperFill(new FakeHttp().answering("/projects/velocity", """
                {"versions":{"4.0.0":["4.2.0-SNAPSHOT","4.2.0","4.1.1","4.1.0"]}}
                """));

        assertEquals("4.2.0", fill.newestStableVersion("velocity", "4.0.0"));
    }

    @Test
    void n4100IsNewerThan490WhichIsTheOneThingSortingTextGetsWrong() throws IOException {
        // Lexicographically "4.10.0" < "4.9.0", so a text sort silently installs the older proxy and never fails.
        final PaperFill fill = new PaperFill(new FakeHttp().answering("/projects/velocity", """
                {"versions":{"4.0.0":["4.9.0","4.10.0","4.8.3"]}}
                """));

        assertEquals("4.10.0", fill.newestStableVersion("velocity", "4.0.0"));
    }

    @Test
    void thePositionInTheResponseDecidesNothing() throws IOException {
        // Fill lists newest first today; believing that as a rule risks quietly running an older proxy.
        final PaperFill fill = new PaperFill(new FakeHttp().answering("/projects/velocity", """
                {"versions":{"4.0.0":["4.0.0","4.1.0","4.1.1"]}}
                """));

        assertEquals("4.1.1", fill.newestStableVersion("velocity", "4.0.0"));
    }

    @Test
    void aFamilyCarryingOnlySnapshotsFailsAndNeverFallsBackToAnotherFamily() {
        // A silent fallback to 3.0.0 would move the network to a different Velocity major, unasked, and must not.
        final PaperFill fill = new PaperFill(new FakeHttp().answering("/projects/velocity", """
                {"versions":{"4.0.0":["4.2.0-SNAPSHOT","4.1.2-SNAPSHOT"],"3.0.0":["3.5.1"]}}
                """));

        final IOException failure =
                assertThrows(IOException.class, () -> fill.newestStableVersion("velocity", "4.0.0"));
        assertTrue(failure.getMessage().contains("4.0.0"), failure.getMessage());
        assertTrue(
                failure.getMessage().contains("4.2.0-SNAPSHOT"),
                "the message has to show what it did find, or it names no way forward: " + failure.getMessage());
        assertFalse(failure.getMessage().contains("3.5.1"), failure.getMessage());
    }

    @Test
    void anUnknownFamilyNamesTheFamiliesThatDoExist() {
        final PaperFill fill = new PaperFill(new FakeHttp().answering("/projects/velocity", """
                {"versions":{"3.0.0":["3.5.1"]}}
                """));

        final IOException failure =
                assertThrows(IOException.class, () -> fill.newestStableVersion("velocity", "4.0.0"));
        assertTrue(failure.getMessage().contains("4.0.0"), failure.getMessage());
        assertTrue(
                failure.getMessage().contains("3.0.0"),
                "a family name is easy to get wrong precisely because it is not a version, so the"
                        + " message has to list the ones Fill knows: " + failure.getMessage());
    }

    @Test
    void aReleaseCandidateIsNotAReleaseInEitherProject() throws IOException {
        // Paper's 26.2 family carries 26.2 and 26.2-rc-2, so the Paper side is an exact version, not a family.
        final PaperFill fill = new PaperFill(new FakeHttp().answering("/projects/paper", """
                {"versions":{"26.2":["26.2.1-pre1","26.2-rc-2","26.2"]}}
                """));

        assertEquals("26.2", fill.newestStableVersion("paper", "26.2"));
    }
}
