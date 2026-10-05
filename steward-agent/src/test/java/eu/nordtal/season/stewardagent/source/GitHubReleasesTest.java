package eu.nordtal.season.stewardagent.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/** The releases API, against what {@code nordtal/season-2} and the fork really published. */
class GitHubReleasesTest {

    @Test
    void theSeasonReleasesSevenAssetsAreReadSizesIncluded() throws IOException {
        final GitHubReleases github =
                new GitHubReleases(new FakeHttp().serving("/releases/latest", "github-season-v0.1.0.json"));

        final GitHubReleases.Release release = github.latest("nordtal/season-2");

        assertEquals("v0.1.0", release.tag());
        assertFalse(release.prerelease());
        assertEquals(7, release.assets().size());

        final GitHubReleases.Asset smp = release.asset("smp-0.1.0.jar");
        assertNotNull(smp);
        // A tiny jar is the scaffold's two log lines, not a real build; only the size says so.
        assertEquals(51_273, smp.size());
        assertTrue(
                smp.url().toString().startsWith("https://github.com/nordtal/season-2/releases/download/"),
                smp.url().toString());
    }

    @Test
    void theForksTagHasNoLeadingVAndThatIsReadRatherThanAssumed() throws IOException {
        final GitHubReleases github =
                new GitHubReleases(new FakeHttp().serving("/releases/latest", "github-display-tags.json"));

        final GitHubReleases.Release release = github.latest("nordtal/papermc-display-tags");

        assertEquals("2.0.0", release.tag());
        assertNotNull(release.asset("papermc-display-tags-2.0.0.jar"));
    }

    @Test
    void thereIsOneEndpointAndItIsReleasesLatestATagCannotBeAskedFor() throws IOException {
        // There is no pin and no tags endpoint: the one call goes to the endpoint that skips drafts and pre-releases.
        final FakeHttp http = new FakeHttp().serving("/releases/", "github-season-v0.1.0.json");
        final GitHubReleases github = new GitHubReleases(http);

        github.latest("nordtal/season-2");

        assertEquals(1, http.requested().size());
        assertEquals(
                "https://api.github.com/repos/nordtal/season-2/releases/latest",
                http.requested().get(0).toString());
    }

    @Test
    void aTextAssetIsReadThroughItsRedirectAndTheRedirectTargetIsNeverKept() throws IOException {
        final FakeHttp http = new FakeHttp()
                .serving("/releases/latest", "github-season-v0.1.0.json")
                .answering(".zip.sha1", "  6f1ed002ab5595859014ebf0951522d9d0f2ee34\n");
        final GitHubReleases github = new GitHubReleases(http);

        final GitHubReleases.Release release = github.latest("nordtal/season-2");
        final GitHubReleases.Asset sha1 = release.asset("nordtal-resource-pack-0.1.0.zip.sha1");
        assertNotNull(sha1);

        // Stripped: the file ends in a newline, and 41 characters fails proxy's "40 hex characters" validation.
        assertEquals("6f1ed002ab5595859014ebf0951522d9d0f2ee34", github.readText(sha1));
    }
}
