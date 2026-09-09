package eu.nordtal.s2.updater.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.s2.updater.http.Http;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The PaperMC Fill v3 API: the newest {@code STABLE} build of a Paper or Velocity version.
 *
 * <h2>Builds move; the Minecraft version does not, and Velocity's minor does</h2>
 * For Paper this class follows <em>builds</em> within a version that stays where it is. A new
 * Minecraft version is a season decision - every plugin in the org is compiled against exactly one
 * - so {@link eu.nordtal.s2.common.Platform#MINECRAFT} is read as an exact version and never moved
 * from here.
 *
 * <p>For Velocity it also follows the <em>version</em>, inside one major. Fill groups versions into
 * families ({@code GET /v3/projects/velocity} answers {@code "4.0.0": ["4.1.2-SNAPSHOT", "4.1.1",
 * …]}), which is what {@link #newestStableVersion} reads; Velocity's minors do not move the
 * Minecraft protocol, so what the season decision protects is not at stake there. What <em>is</em>
 * at stake is the API {@code network-control} was compiled against, and the resolver says so in its
 * report when the two part company.</p>
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

    /** The only channel a production network follows. Fill also publishes {@code ALPHA}. */
    private static final String STABLE = "STABLE";

    /**
     * A version this module is willing to install: digits and dots, nothing else.
     * <p>
     * That is exactly the filter the family resolution needs and it is worth being blunt about
     * what it excludes - {@code 4.1.2-SNAPSHOT}, {@code 26.2-rc-2}, {@code 1.21.11-pre5}. An
     * updater that pulls somebody's snapshot onto a server people paid to play on is what this
     * module is arranged to prevent; {@code Modrinth}'s {@code version_type: release} filter is the
     * same rule from the other end.
     * </p>
     */
    private static final Pattern RELEASE_VERSION = Pattern.compile("[0-9]+(\\.[0-9]+)*");

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
     * The newest release version inside one of Fill's version families.
     *
     * <p>Fill's own grouping, read off {@code GET /v3/projects/<project>}: its {@code versions}
     * object maps a family name onto the versions in it, newest first in every response seen so
     * far. The family name is not a version anybody runs - Velocity's whole 4.x line is called
     * {@code 4.0.0} - so it is looked up rather than installed.</p>
     *
     * <p><b>Compared as numbers, never as text.</b> {@code 4.10.0} is newer than {@code 4.9.0} and
     * sorts below it lexicographically; the position in the response is not trusted either, because
     * "newest first" is an observation about today's payload and this is the one place where being
     * wrong means quietly installing an older proxy for as long as the minor stays two digits.</p>
     *
     * @throws IOException when the family is unknown or carries no release version at all - never a
     *                     fallback onto another family, which would move the network to a different
     *                     Velocity major without anybody asking for it
     */
    public @NotNull String newestStableVersion(final @NotNull String project,
                                               final @NotNull String family) throws IOException {

        final URI uri = URI.create(API + project);
        final String what = "PaperMC Fill " + project;
        final JsonObject versions = Json.child(Json.object(http.get(uri), what), "versions");
        if (versions == null) {
            throw new IOException(what + ": the project response carries no 'versions' object."
                    + " The API's shape has changed - check " + uri);
        }

        final JsonElement inFamily = versions.get(family);
        if (inFamily == null || !inFamily.isJsonArray()) {
            throw new IOException(what + ": there is no version family '" + family + "'. Fill knows "
                    + versions.keySet() + " - a family is Fill's name for a major and is not a"
                    + " version anybody runs, so check it against " + uri);
        }

        final List<String> released = new ArrayList<>();
        final List<String> all = new ArrayList<>();
        for (final JsonElement element : inFamily.getAsJsonArray()) {
            final String version = element.getAsString();
            all.add(version);
            if (RELEASE_VERSION.matcher(version).matches()) {
                released.add(version);
            }
        }

        String newest = null;
        for (final String version : released) {
            if (newest == null || compare(version, newest) > 0) {
                newest = version;
            }
        }
        if (newest == null) {
            throw new IOException(what + ": family '" + family + "' carries no released version -"
                    + " only " + all + ". A snapshot or release candidate is never installed, so"
                    + " there is nothing here to run; this is not a reason to fall back to another"
                    + " family.");
        }
        return newest;
    }

    /** Component by component, as numbers, with a missing component reading as zero. */
    private static int compare(final String left, final String right) {
        final String[] mine = left.split("\\.");
        final String[] theirs = right.split("\\.");
        for (int i = 0; i < Math.max(mine.length, theirs.length); i++) {
            final int a = i < mine.length ? Integer.parseInt(mine[i]) : 0;
            final int b = i < theirs.length ? Integer.parseInt(theirs[i]) : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        return 0;
    }
}
