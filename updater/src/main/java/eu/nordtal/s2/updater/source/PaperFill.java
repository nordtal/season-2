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
 * <p>For Paper this follows <em>builds</em> within a version that never moves from here, because a
 * new Minecraft version is a season decision. For Velocity it also follows the <em>version</em>
 * inside one of Fill's families ({@link #newestStableVersion}), since Velocity's minors do not move
 * the Minecraft protocol - only the API {@code network-control} was compiled against, which the
 * resolver reports.</p>
 *
 * <p>Following builds automatically has the widest blast radius of anything here: one build changes
 * the platform under all four servers at once and nothing in this repository tests against it. The
 * report puts the build number in front of a person before anything restarts.</p>
 *
 * <p>The filename comes from {@code downloads."server:default".name} and is never built by hand:
 * {@code deploy/minecraft/entrypoint.sh} reads the same field, and two programs constructing the
 * name separately is how a server comes to run one jar while something believes it installed
 * another.</p>
 */
public final class PaperFill {

    private static final String API = "https://fill.papermc.io/v3/projects/";

    /** The only channel a production network follows. Fill also publishes {@code ALPHA}. */
    private static final String STABLE = "STABLE";

    /**
     * A version this module is willing to install: digits and dots, nothing else - so no
     * {@code 4.1.2-SNAPSHOT}, {@code 26.2-rc-2} or {@code 1.21.11-pre5} ever reaches a server.
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

        // The channel filter decides, not the position: the newest build of a version can be
        // EXPERIMENTAL, and taking builds[0] blindly is how a proxy ends up on one.
        for (final JsonElement element : builds) {
            final JsonObject build = element.getAsJsonObject();
            if (!STABLE.equals(Json.optionalString(build, "channel"))) {
                continue;
            }

            final JsonObject downloads = Json.child(build, "downloads");
            final JsonObject download = downloads == null ? null : Json.child(downloads, SERVER_DEFAULT);
            if (download == null) {
                // A STABLE build with no server jar has not been seen; if it happens, the next
                // stable build behind it is the right answer.
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
     * <p>Fill's own grouping, read off {@code GET /v3/projects/<project>}. A family name is not a
     * version anybody runs - Velocity's whole 4.x line is called {@code 4.0.0} - so it is looked up
     * rather than installed.</p>
     *
     * <p><b>Compared as numbers, never as text</b>, and never by position in the response:
     * {@code 4.10.0} is newer than {@code 4.9.0} and sorts below it lexicographically, and being
     * wrong here quietly installs an older proxy.</p>
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
