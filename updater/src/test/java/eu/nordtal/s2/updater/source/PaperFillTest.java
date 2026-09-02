package eu.nordtal.s2.updater.source;

import eu.nordtal.s2.updater.http.FakeHttp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @DisplayName("a pinned build is fetched as that build, STABLE or not, from the single-build endpoint")
    void pinnedBuild() throws IOException {
        // The same endpoint entrypoint.sh seeds an empty cache from: one object, not a list.
        final String body = """
                {"id":119,"channel":"STABLE","time":"2026-08-20T00:00:00Z","downloads":{
                   "server:default":{"name":"paper-26.2-119.jar","url":"https://x/119",
                                     "checksums":{"sha256":"cc"}}}}
                """;
        final FakeHttp http = new FakeHttp()
                .answering("/builds/119", body)
                .serving("/builds", "fill-paper-26.2.json");
        final PaperFill fill = new PaperFill(http);

        final RemoteFile pinned = fill.resolve("paper", "26.2", "119");
        assertEquals("paper-26.2-119.jar", pinned.fileName());
        assertEquals("119", pinned.version());
        assertTrue(http.requested().getLast().toString().endsWith("/builds/119"));

        // 'latest' goes to the list, as before.
        assertEquals("paper-26.2-121.jar", fill.resolve("paper", "26.2", PaperFill.LATEST).fileName());
    }

    @Test
    @DisplayName("a version with no stable build at all is an error naming the version")
    void refusesWhenNothingIsStable() {
        final PaperFill fill = new PaperFill(new FakeHttp().answering("/builds", "[]"));

        final IOException failure = assertThrows(IOException.class, () -> fill.newestStable("paper", "27.0"));
        assertTrue(failure.getMessage().contains("27.0"), failure.getMessage());
    }
}
