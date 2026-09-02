package eu.nordtal.s2.updater.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.s2.updater.http.Http;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;

/**
 * The PaperMC Fill v3 API: the newest {@code STABLE} build of a pinned Paper or Velocity version.
 *
 * <h2>Builds move, versions do not</h2>
 * This class follows <em>builds</em> within a version that stays where it is. A new Minecraft
 * version is a season decision - every plugin in the org is compiled against exactly one - so
 * {@code minecraft-version} and {@code velocity-version} in {@code updater.yml} are read, never
 * written.
 *
 * <p>Following builds automatically is nonetheless the entry in
 * docs/updater.md#where-versions-come-from with the widest blast radius: one build changes the
 * platform under all four servers at once, and nothing in this repository tests against it. It is
 * therefore also the first thing to look at when something breaks after an update, and the report
 * puts the build number in front of a person before anything restarts.</p>
 *
 * <h2>The filename comes from the API</h2>
 * {@code downloads."server:default".name} is {@code paper-26.2-121.jar} - the exact shape
 * {@code deploy/minecraft/entrypoint.sh} builds by hand when it seeds an empty cache from
 * {@code SERVER_BUILD}, and the shape it globs for ({@code paper-26.2-*.jar}) on every start after
 * that. Reading it instead of building it again keeps the two in step without either knowing about
 * the other; since 2026-09-02 the entrypoint runs whatever build of the version this class put there.
 */
public final class PaperFill {

    private static final String API = "https://fill.papermc.io/v3/projects/";

    /** The value of {@code paper-build} / {@code velocity-build} that means "follow STABLE". */
    public static final String LATEST = "latest";

    /** The only channel a production network follows. Fill also publishes {@code ALPHA}. */
    private static final String STABLE = "STABLE";

    /** The download the servers run. Fill also publishes {@code mojang-mapped} builds. */
    private static final String SERVER_DEFAULT = "server:default";

    private final Http http;

    public PaperFill(final Http http) {
        this.http = http;
    }

    /**
     * @param project {@code paper} or {@code velocity} - the same word {@code SERVER_KIND} takes
     *                in the entrypoint, and the id used both as the artifact id and in the URL.
     * @param version the pinned version, e.g. {@code 26.2} or {@code 4.1.1}.
     */
    public @NotNull RemoteFile newestStable(final @NotNull String project, final @NotNull String version)
            throws IOException {

        final URI uri = URI.create(API + project + "/versions/" + version + "/builds");
        final String what = "PaperMC Fill " + project + " " + version;
        final JsonArray builds = Json.array(http.get(uri), what);

        // Newest first in every response seen so far, but the channel filter is what decides, not
        // the position: the newest build of a version can be EXPERIMENTAL, and taking builds[0]
        // blindly is how a proxy ends up on one.
        for (final JsonElement element : builds) {
            final JsonObject build = element.getAsJsonObject();
            if (!STABLE.equals(Json.optionalString(build, "channel"))) {
                continue;
            }

            final JsonObject downloads = Json.child(build, "downloads");
            final JsonObject download = downloads == null ? null : Json.child(downloads, SERVER_DEFAULT);
            if (download == null) {
                // A STABLE build that publishes no server jar is not a thing that has been seen.
                // If it happens, skipping to the next stable build is right - there is a working
                // one behind it - and it is worth neither an exception nor silence.
                continue;
            }

            final JsonObject checksums = Json.child(download, "checksums");
            final String sha256 = checksums == null ? null : Json.optionalString(checksums, "sha256");

            return new RemoteFile(
                    project,
                    String.valueOf(Json.number(build, "id", -1)),
                    Json.string(download, "name", what),
                    URI.create(Json.string(download, "url", what)),
                    sha256 == null ? null : Checksum.sha256(sha256));
        }

        throw new IOException(what + ": no " + STABLE + " build with a '" + SERVER_DEFAULT
                + "' download. Check the version against https://fill.papermc.io/v3/projects/"
                + project + " - a version that has been dropped answers with builds for a while"
                + " and then stops.");
    }

    /**
     * {@link #newestStable} or one exact build, depending on the pin - the one entry point the
     * resolver calls.
     *
     * @param build {@link #LATEST}, or a build number as text.
     */
    public @NotNull RemoteFile resolve(final @NotNull String project, final @NotNull String version,
                                       final @NotNull String build) throws IOException {
        return LATEST.equals(build) ? newestStable(project, version) : exactBuild(project, version, build);
    }

    /**
     * One pinned build, whatever its channel: {@code /builds/<n>} - the same call
     * {@code entrypoint.sh} makes to seed an empty cache. A pin is a person's decision, so a
     * build that is not STABLE is taken and said, not refused: the entrypoint warns the same way.
     */
    public @NotNull RemoteFile exactBuild(final @NotNull String project, final @NotNull String version,
                                          final @NotNull String build) throws IOException {

        final URI uri = URI.create(API + project + "/versions/" + version + "/builds/" + build);
        final String what = "PaperMC Fill " + project + " " + version + " build " + build;
        final JsonObject json = Json.object(http.get(uri), what);

        final JsonObject downloads = Json.child(json, "downloads");
        final JsonObject download = downloads == null ? null : Json.child(downloads, SERVER_DEFAULT);
        if (download == null) {
            throw new IOException(what + ": no '" + SERVER_DEFAULT + "' download. Check the pin"
                    + " against https://fill.papermc.io/v3/projects/" + project + "/versions/" + version
                    + "/builds");
        }
        final JsonObject checksums = Json.child(download, "checksums");
        final String sha256 = checksums == null ? null : Json.optionalString(checksums, "sha256");

        return new RemoteFile(
                project,
                String.valueOf(Json.number(json, "id", -1)),
                Json.string(download, "name", what),
                URI.create(Json.string(download, "url", what)),
                sha256 == null ? null : Checksum.sha256(sha256));
    }
}
