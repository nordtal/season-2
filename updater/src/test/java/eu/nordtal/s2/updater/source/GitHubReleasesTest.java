package eu.nordtal.s2.updater.source;

import eu.nordtal.s2.updater.http.FakeHttp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The releases API, against what {@code nordtal/season-2} and the fork really published. */
class GitHubReleasesTest {

    @Test
    @DisplayName("the season release's seven assets are read, sizes included")
    void readsTheSeasonRelease() throws IOException {
        final GitHubReleases github = new GitHubReleases(
                new FakeHttp().serving("/releases/latest", "github-season-v0.1.0.json"));

        final GitHubReleases.Release release = github.fetch("nordtal/season-2", GitHubReleases.LATEST);

        assertEquals("v0.1.0", release.tag());
        assertFalse(release.prerelease());
        assertEquals(7, release.assets().size());

        final GitHubReleases.Asset smp = release.asset("smp-0.1.0.jar");
        assertNotNull(smp);
        // The finding this whole module exists for, recorded as a fixture: v0.1.0's smp jar is
        // 51 273 bytes - the scaffold's two log lines - while the same version built from main
        // that day was 4 820 904. Nothing in the API says so, which is the point: only a person
        // comparing the number against a build notices, and no person was going to.
        assertEquals(51_273, smp.size());
        assertTrue(smp.url().toString().startsWith("https://github.com/nordtal/season-2/releases/download/"),
                smp.url().toString());
    }

    @Test
    @DisplayName("the fork's tag has no leading v, and that is read rather than assumed")
    void readsTheForkRelease() throws IOException {
        final GitHubReleases github = new GitHubReleases(
                new FakeHttp().serving("/releases/latest", "github-display-tags.json"));

        final GitHubReleases.Release release =
                github.fetch("nordtal/papermc-display-tags", GitHubReleases.LATEST);

        assertEquals("2.0.0", release.tag());
        assertNotNull(release.asset("papermc-display-tags-2.0.0.jar"));
    }

    @Test
    @DisplayName("latest and a pinned tag are different endpoints")
    void pinningATagUsesTheTagsEndpoint() throws IOException {
        final FakeHttp http = new FakeHttp().serving("/releases/", "github-season-v0.1.0.json");
        final GitHubReleases github = new GitHubReleases(http);

        github.fetch("nordtal/season-2", GitHubReleases.LATEST);
        github.fetch("nordtal/season-2", "v0.1.0");

        assertEquals("https://api.github.com/repos/nordtal/season-2/releases/latest",
                http.requested().get(0).toString());
        assertEquals("https://api.github.com/repos/nordtal/season-2/releases/tags/v0.1.0",
                http.requested().get(1).toString());
    }

    @Test
    @DisplayName("a text asset is read through its redirect, and the redirect target is never kept")
    void readsASmallTextAsset() throws IOException {
        final FakeHttp http = new FakeHttp()
                .serving("/releases/latest", "github-season-v0.1.0.json")
                .answering(".zip.sha1", "  6f1ed002ab5595859014ebf0951522d9d0f2ee34\n");
        final GitHubReleases github = new GitHubReleases(http);

        final GitHubReleases.Release release = github.fetch("nordtal/season-2", GitHubReleases.LATEST);
        final GitHubReleases.Asset sha1 = release.asset("nordtal-resource-pack-0.1.0.zip.sha1");
        assertNotNull(sha1);

        // Stripped, because the file ends in a newline and a 41-character "40 hex characters"
        // fails network-control's own validation with a message about the alphabet.
        assertEquals("6f1ed002ab5595859014ebf0951522d9d0f2ee34", github.readText(sha1));
    }
}
