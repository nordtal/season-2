package eu.nordtal.s2.steward.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.s2.steward.http.Http;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The PaperMC Fill v3 API: the newest {@code STABLE} build of a Paper or Velocity version.
 *
 * The filename comes from {@code downloads."server:default".name}, never built by hand.
 */
public final class PaperFill {

    private static final String API = "https://fill.papermc.io/v3/projects/";

    /** The only channel followed. Fill also publishes {@code ALPHA}. */
    private static final String STABLE = "STABLE";

    /** A version this module is willing to install: digits and dots, so no snapshot, rc or pre-release. */
    private static final Pattern RELEASE_VERSION = Pattern.compile("[0-9]+(\\.[0-9]+)*");

    /** The download the servers run. Fill also publishes {@code mojang-mapped} builds. */
    private static final String SERVER_DEFAULT = "server:default";

    private final Http http;

    public PaperFill(final Http http) {
        this.http = http;
    }

    /**
     * Creates a source for one project version.
     *
     * @param project {@code paper} or {@code velocity}, used as the artifact id and in the URL
     * @param version the version, for example {@code 26.2} or {@code 4.1.1}
     */
    public RemoteFile newestStable(final String project, final String version) throws IOException {

        final URI uri = URI.create(API + project + "/versions/" + version + "/builds");
        final String what = "PaperMC Fill " + project + " " + version;
        final JsonArray builds = ApiFields.array(http.get(uri), what);

        // The channel filter decides, not the position: the newest build can be EXPERIMENTAL.
        for (final JsonElement element : builds) {
            final JsonObject build = element.getAsJsonObject();
            if (!STABLE.equals(ApiFields.optionalString(build, "channel"))) {
                continue;
            }

            final JsonObject downloads = ApiFields.child(build, "downloads");
            final JsonObject download = downloads == null ? null : ApiFields.child(downloads, SERVER_DEFAULT);
            if (download == null) {
                // A STABLE build with no server jar has not been seen; the next stable build behind it is the answer.
                continue;
            }

            final JsonObject checksums = ApiFields.child(download, "checksums");
            final String sha256 = checksums == null ? null : ApiFields.optionalString(checksums, "sha256");

            return new RemoteFile(
                    project,
                    String.valueOf(ApiFields.number(build, "id", -1)),
                    ApiFields.string(download, "name", what),
                    URI.create(ApiFields.string(download, "url", what)),
                    sha256 == null ? null : Checksum.sha256(sha256));
        }

        throw new IOException(what + ": no " + STABLE + " build with a '" + SERVER_DEFAULT
                + "' download. Check the version against https://fill.papermc.io/v3/projects/"
                + project + " - a version that has been dropped answers with builds for a while"
                + " and then stops.");
    }

    /**
     * The newest release version inside one of Fill's version families, compared as numbers.
     *
     * @throws IOException when the family is unknown or carries no release version, never falling back to another
     */
    public String newestStableVersion(final String project, final String family) throws IOException {

        final URI uri = URI.create(API + project);
        final String what = "PaperMC Fill " + project;
        final JsonObject versions = ApiFields.child(ApiFields.object(http.get(uri), what), "versions");
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

    private static final Pattern DOT = Pattern.compile("\\.");

    /** Component by component, as numbers, with a missing component reading as zero. */
    private static int compare(final String left, final String right) {
        final String[] mine = DOT.splitAsStream(left).toArray(String[]::new);
        final String[] theirs = DOT.splitAsStream(right).toArray(String[]::new);
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
