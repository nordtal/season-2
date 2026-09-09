package eu.nordtal.s2.updater.source;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.s2.updater.http.Http;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
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

    /** What the endpoint is called, and all a failure message can honestly name. */
    private static final String LATEST = "latest";

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
     * @param prerelease read off the payload rather than assumed. {@code /releases/latest} is
     *                   documented not to return one, and since 2026-09-09 that is the only
     *                   endpoint this class asks - so this should always be {@code false}. It is
     *                   still reported, because "GitHub said something we did not expect" belongs
     *                   in a report and not in a comment claiming it cannot happen.
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
     * The newest published release of a repository.
     *
     * <p>There is no way to ask for a particular tag, and that is the decision, taken 2026-09-09
     * along with the removal of {@code IMAGE_TAG} and the {@code season-release} config key: a
     * fetch-by-tag exists only to serve a pin, and a pin is a version number kept somewhere other
     * than {@code gradle.properties}. Every one this project kept went stale. A bad release is
     * corrected by publishing a better one, and the cost - there is no way back except forward - is
     * the same one already accepted for the Paper build.</p>
     *
     * <p>{@code /releases/latest} SKIPS DRAFTS AND PRE-RELEASES by GitHub's own definition. That is
     * wanted, and it is also the trap that replaces the old one: a release left as a draft is
     * invisible here, so an update that "did not arrive" is usually a release nobody published.</p>
     *
     * @param repo {@code owner/name}.
     */
    public @NotNull Release latest(final @NotNull String repo) throws IOException {
        final URI uri = URI.create(API + repo + "/releases/latest");

        final String what = "GitHub release " + repo + "@" + LATEST;
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
