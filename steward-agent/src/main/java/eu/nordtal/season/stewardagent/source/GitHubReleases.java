package eu.nordtal.season.stewardagent.source;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The GitHub releases API, for the season repository.
 *
 * An asset carries no digest; the pack's SHA-1 is its own 41-byte asset, read rather than computed.
 */
public final class GitHubReleases {

    private static final String API = "https://api.github.com/repos/";

    /** What the endpoint is called, and all a failure message can honestly name. */
    private static final String LATEST = "latest";

    private final Http http;

    public GitHubReleases(final Http http) {
        this.http = http;
    }

    /**
     * One published release and everything hanging off it.
     *
     * @param tag the tag as GitHub reports it, since only season-2 has a leading {@code v}
     * @param prerelease read off the payload, and expected to be {@code false} for {@code /releases/latest}
     */
    public record Release(String tag, boolean prerelease, List<Asset> assets) {

        /** An asset by exact name, or {@code null}. */
        public @Nullable Asset asset(final String name) {
            return assets.stream()
                    .filter(candidate -> candidate.name().equals(name))
                    .findFirst()
                    .orElse(null);
        }
    }

    public record Asset(String name, URI url, long size) {}

    /**
     * The newest published release of a repository; there is no fetch by tag, since nothing pins a release.
     *
     * @param repo {@code owner/name}; a draft or pre-release is invisible here
     */
    public Release latest(final String repo) throws IOException {
        final URI uri = URI.create(API + repo + "/releases/latest");

        final String what = "GitHub release " + repo + "@" + LATEST;
        final JsonObject payload = ApiFields.object(http.get(uri), what);

        final List<Asset> assets = new ArrayList<>();
        final JsonElement raw = payload.get("assets");
        if (raw != null && raw.isJsonArray()) {
            for (final JsonElement element : raw.getAsJsonArray()) {
                final JsonObject asset = element.getAsJsonObject();
                assets.add(new Asset(
                        ApiFields.string(asset, "name", what),
                        URI.create(ApiFields.string(asset, "browser_download_url", what)),
                        ApiFields.number(asset, "size", -1)));
            }
        }

        return new Release(
                ApiFields.string(payload, "tag_name", what),
                ApiFields.bool(payload, "prerelease", false),
                List.copyOf(assets));
    }

    /**
     * Reads a small text asset, the pack's {@code .sha1}, following GitHub's redirect without keeping the signed URL.
     */
    public String readText(final Asset asset) throws IOException {
        return http.get(asset.url()).strip();
    }
}
