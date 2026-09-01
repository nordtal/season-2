package eu.nordtal.s2.updater.source;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.s2.updater.http.Http;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The GitHub releases API, for the two repositories that publish jars we run: our own
 * {@code nordtal/season-2} and our fork {@code nordtal/papermc-display-tags}.
 *
 * <h2>What {@code latest} means here</h2>
 * {@code /releases/latest} is GitHub's own definition, and it is the one we want: <b>drafts and
 * pre-releases are excluded.</b> The trap is the same sentence read the other way - a release left
 * as a draft does not exist for this module, so "the update did not arrive" and "nobody pressed
 * Publish" look identical from inside the container. The report names the tag it resolved, which
 * is what makes them distinguishable to a person.
 *
 * <h2>No checksums</h2>
 * A release asset carries a name, a size and a download URL. No digest of any kind (checked
 * against the live API on 2026-09-01) - see {@link Checksum} for what follows from that. The one
 * exception is our own resource pack, which ships its SHA-1 as a <em>separate asset</em> because
 * the Minecraft client demands one; that file is 41 bytes and is read, not computed.
 */
public final class GitHubReleases {

    private static final String API = "https://api.github.com/repos/";

    /** The literal an operator writes to mean "whatever is newest", rather than a tag. */
    public static final String LATEST = "latest";

    private final Http http;

    public GitHubReleases(final Http http) {
        this.http = http;
    }

    /**
     * One published release and everything hanging off it.
     *
     * @param tag        the tag as GitHub reports it - {@code v0.2.0} for season-2, {@code 2.0.0}
     *                   for display-tags. Reported rather than assumed, because the two
     *                   repositories disagree about the leading {@code v} and always have.
     * @param prerelease kept even though {@code /releases/latest} never returns one: a release
     *                   fetched <em>by tag</em> can be a pre-release, and that is worth saying out
     *                   loud in a report before somebody deploys it.
     */
    public record Release(@NotNull String tag, boolean prerelease, @NotNull List<Asset> assets) {

        /** An asset by exact name, or {@code null}. */
        public @Nullable Asset asset(final @NotNull String name) {
            return assets.stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElse(null);
        }
    }

    public record Asset(@NotNull String name, @NotNull URI url, long size) {
    }

    /**
     * @param repo    {@code owner/name}.
     * @param release {@link #LATEST}, or an exact tag.
     */
    public @NotNull Release fetch(final @NotNull String repo, final @NotNull String release) throws IOException {
        final URI uri = LATEST.equals(release)
                ? URI.create(API + repo + "/releases/latest")
                // Tags are user-supplied and may contain anything a git ref may contain. Encoded so
                // that a tag with a slash in it is a path segment and not a different endpoint.
                : URI.create(API + repo + "/releases/tags/"
                        + URLEncoder.encode(release, StandardCharsets.UTF_8).replace("+", "%20"));

        final String what = "GitHub release " + repo + "@" + release;
        final JsonObject payload = Json.object(http.get(uri), what);

        final List<Asset> assets = new ArrayList<>();
        final JsonElement raw = payload.get("assets");
        if (raw != null && raw.isJsonArray()) {
            for (final JsonElement element : raw.getAsJsonArray()) {
                final JsonObject asset = element.getAsJsonObject();
                assets.add(new Asset(
                        Json.string(asset, "name", what),
                        URI.create(Json.string(asset, "browser_download_url", what)),
                        Json.number(asset, "size", -1)));
            }
        }

        return new Release(
                Json.string(payload, "tag_name", what),
                Json.bool(payload, "prerelease", false),
                List.copyOf(assets));
    }

    /**
     * Reads the content of a small text asset - which in practice is one file, the pack's
     * {@code .sha1}.
     * <p>
     * The URL is the {@code github.com/.../releases/download/...} one, which answers a 302 to a
     * signed {@code release-assets.githubusercontent.com} address that expires within the hour.
     * That redirect is followed here and the resolved address is <b>never</b> kept: it is exactly
     * the URL that must not end up in {@code pack.yml}, and a value that works this afternoon and
     * fails tonight is the worst kind.
     * </p>
     */
    public @NotNull String readText(final @NotNull Asset asset) throws IOException {
        return http.get(asset.url()).strip();
    }
}
