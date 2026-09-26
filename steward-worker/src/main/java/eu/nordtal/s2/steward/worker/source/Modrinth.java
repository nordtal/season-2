package eu.nordtal.s2.steward.worker.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.s2.steward.worker.http.Http;
import eu.nordtal.s2.steward.worker.http.HttpException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The Modrinth v2 API, for the third-party plugins the network runs: PacketEvents,
 * Simple Voice Chat - on the two backends and on the proxy - and CoreProtect.
 *
 * <h2>Why Modrinth and not each project's own releases</h2>
 * Because Modrinth is the only source that answers the question actually being asked. GitHub
 * releases say "here is version 2.13.0"; Modrinth says "here is what is tagged for Minecraft 26.2
 * on paper", which is a different and much narrower set. Measured 2026-09-01 against the live API,
 * that filter returns <b>exactly one</b> version for each of the two projects, and both filenames
 * are byte-for-byte what {@code compose.yml} pins by hand today.
 *
 * <h2>Two traps, both present in the real payloads</h2>
 * <ul>
 *   <li><b>PacketEvents ships a {@code -sources.jar} in the same version.</b> Modrinth marks the
 *       real artefact {@code "primary": true}; anything that matches on {@code .jar} alone puts a
 *       sources jar into a plugins folder, where it loads as a plugin with no code in it. This
 *       class refuses a version with no primary file rather than guessing - see below.</li>
 *   <li><b>The list is not documented as ordered.</b> It comes back newest-first in practice; it
 *       is sorted here by {@code date_published} anyway, because "in practice" is not a
 *       guarantee and the failure mode is installing a two-year-old build silently.</li>
 * </ul>
 *
 * <h2>Only {@code release}, and one artefact that is named</h2>
 * {@code version_type} is one of {@code release}, {@code beta}, {@code alpha}. Only the first is
 * considered. A worker that pulls somebody's alpha onto a server people paid to play on, at
 * three in the morning, without being asked, is the thing this whole module is arranged to avoid.
 *
 * <p>The rule is unchanged for every artefact but one. {@link #PRE_RELEASE_EXCEPTIONS} is that one,
 * written out by name rather than expressed as a switch - see its own note for why a setting would
 * have been the wrong shape.</p>
 */
public final class Modrinth {

    private static final String API = "https://api.modrinth.com/v2/project/";

    private static final String SEARCH = "https://api.modrinth.com/v2/search";

    /** Where a project's own page lives, which is the link the interface offers next to a hit. */
    private static final String PAGE = "https://modrinth.com/plugin/";

    private static final String VERSION_FILE = "https://api.modrinth.com/v2/version_file/";
    private static final String PROJECTS = "https://api.modrinth.com/v2/projects";

    /**
     * How many hits one search asks for.
     *
     * <p>Twenty, and it is a constant rather than a parameter because the caller that would set it
     * is a browser and this is somebody else's API. A person looking for a plugin types its name;
     * a person scrolling to hit ninety has not found it and needs a better search term, not a
     * longer list.</p>
     */
    public static final int SEARCH_LIMIT = 20;

    /**
     * The artefact ids for which a {@code beta} or an {@code alpha} counts, and there is exactly
     * one: {@code voicechat-velocity}, Simple Voice Chat's proxy half.
     *
     * <h2>Why this one and why not a setting</h2>
     * Because the reason is not "the alpha is newer". <b>The project has never published a
     * {@code release} for Velocity at all</b> - 13 versions since 2022, every one of them
     * {@code alpha} or {@code beta}, newest {@code velocity-2.6.18} of 2026-05-28, and it is the
     * only one of the thirteen tagged for Minecraft 26.2 (queried against the live API,
     * 2026-09-09). So "wait for a release" is not a slower path to the same place; it is a decision
     * never to install this plugin, taken by accident and never written down.
     *
     * <p>The alternative that was rejected is a config key - a {@code minimum-version-type}, or a
     * per-artefact flag in {@code steward.yml}. That would put the decision in a deployed file
     * where an operator can widen it to everything at three in the morning to make one run
     * succeed, and where nothing afterwards records that it was ever narrow. A named constant costs
     * a code change and a red build, which is the price this exception should cost.</p>
     *
     * <p><b>Adding a second entry here is a decision, not a line.</b> {@code ModrinthTest} asserts
     * this set has exactly one member and which one, so a second artefact leaning on the exception
     * fails the build rather than inheriting it. What would have to be true for a new entry is what
     * is true here: not that a pre-release is available, but that a stable one has never existed
     * and there is no reason to expect one.</p>
     */
    public static final List<String> PRE_RELEASE_EXCEPTIONS = List.of("voicechat-velocity");

    /**
     * Modrinth answered, and it has no stable build of this plugin for this Minecraft version.
     *
     * <h2>Why this is a separate exception and not the general one</h2>
     * Because the two are opposite advice. An outage is "this list is not the whole picture, look
     * again later"; this is "there is nothing to look for, and there will not be until somebody
     * else publishes". Both used to come out as a plain {@link IOException}, so a plugin that is
     * simply behind the platform read as a source steward-worker could not reach - which makes the
     * whole of its service {@code SKIPPED}, every run, for as long as the situation lasts. On
     * {@code smp} that means the season jar is never installed either.
     *
     * <p>What it must <b>not</b> become is a way to install something else. There is no fallback
     * here and there is deliberately no config key to allow one: a 26.1 jar on a 26.2 server is
     * not a degraded version of a working plugin.</p>
     */
    public static final class Unsupported extends IOException {

        private static final long serialVersionUID = 1L;

        public Unsupported(final String message) {
            super(message);
        }
    }

    private final Http http;

    public Modrinth(final Http http) {
        this.http = http;
    }

    /**
     * The newest {@code release} of {@code projectId} tagged for {@code gameVersion} on
     * {@code loader}.
     *
     * <p>For an artefact in {@link #PRE_RELEASE_EXCEPTIONS}, and only for those, a {@code beta} or
     * an {@code alpha} counts as well. There is no parameter and no setting for that: the decision
     * is the id, so a caller cannot ask for it on the wrong artefact.</p>
     *
     * @param artifact the id this module knows the plugin by, carried into the {@link RemoteFile}.
     * @throws Unsupported if the filter matches nothing - the plugin has no stable build for this
     *                     Minecraft version, which is a fact about somebody else's release
     *                     schedule rather than a failure of this run
     * @throws IOException if the newest match has no primary file, or the API could not be read.
     *                     Both are refusals rather than fallbacks: installing the 26.1 build
     *                     instead is not a decision a program gets to make.
     */
    public @NotNull RemoteFile newest(
            final @NotNull String artifact,
            final @NotNull String projectId,
            final @NotNull String gameVersion,
            final @NotNull String loader)
            throws IOException {

        // Both filters are JSON arrays inside a query parameter - Modrinth's own documented shape,
        // ?game_versions=["26.2"]&loaders=["paper"] - so the brackets and quotes have to survive
        // encoding as data rather than being taken for URL syntax.
        final URI uri = URI.create(API + projectId + "/version"
                + "?game_versions=" + encode("[\"" + gameVersion + "\"]")
                + "&loaders=" + encode("[\"" + loader + "\"]"));

        final String what = "Modrinth " + artifact + " (" + projectId + ") for " + gameVersion + "/" + loader;
        final JsonArray versions = Json.array(http.get(uri), what);

        // The exception is decided here, from the artefact id alone, so that no caller and no
        // config file can point it at anything else. See PRE_RELEASE_EXCEPTIONS.
        final boolean preReleasesCount = PRE_RELEASE_EXCEPTIONS.contains(artifact);

        final List<JsonObject> releases = new ArrayList<>();
        for (final JsonElement element : versions) {
            final JsonObject version = element.getAsJsonObject();
            if (preReleasesCount || "release".equals(Json.optionalString(version, "version_type"))) {
                releases.add(version);
            }
        }

        if (releases.isEmpty()) {
            throw new Unsupported(what + ": no "
                    + (preReleasesCount ? "version of any kind" : "stable release")
                    + " is tagged for this platform. Either the plugin has not been updated for it"
                    + " yet, or a pre-release is being waited on - neither is something this module"
                    + " may work around by installing a build for a different Minecraft version.");
        }

        // Newest first. A version with an unparseable date sorts last rather than crashing the run:
        // the field is not one we control, and one odd row must not cost the other five artefacts
        // their report.
        releases.sort(
                Comparator.comparing((JsonObject version) -> published(version)).reversed());
        final JsonObject newest = releases.getFirst();

        final JsonObject file = primaryFile(newest);
        if (file == null) {
            throw new IOException(what + ": version " + Json.optionalString(newest, "version_number")
                    + " has no file marked \"primary\": true. Refusing to guess: this project"
                    + " publishes a -sources.jar alongside the real one, and the wrong guess is a"
                    + " plugin folder containing source code.");
        }

        final JsonObject hashes = Json.child(file, "hashes");
        final String sha512 = hashes == null ? null : Json.optionalString(hashes, "sha512");

        return new RemoteFile(
                artifact,
                Json.string(newest, "version_number", what),
                Json.string(file, "filename", what),
                URI.create(Json.string(file, "url", what)),
                sha512 == null ? null : Checksum.sha512(sha512));
    }

    // ---------------------------------------------------------------- the search (season-2-ops/129)

    /**
     * One hit of a plugin search, in the shape the interface draws it.
     *
     * @param projectId   Modrinth's immutable id. <b>This is the identity</b> - what
     *                    {@link #newest} is asked with, and what makes a slug rename cost nothing
     * @param slug        the readable id, which becomes the artefact id of an added plugin
     * @param title       the project's name
     * @param description the one-line summary Modrinth calls {@code description}
     * @param iconUrl     the thumbnail, on {@code cdn.modrinth.com}, or {@code null} for a project
     *                    that has none. <b>The browser loads this from Modrinth directly</b>
     *                    (owner, 2026-09-19) - which is why any Content-Security-Policy in front of
     *                    steward-ui has to allow that host, or the images silently stay blank
     * @param pageUrl     the project's own page, for the link next to the install button
     * @param downloads   how many times it has been downloaded - the only number here that says
     *                    anything about whether a stranger's plugin is worth trusting
     */
    public record Hit(
            @NotNull String projectId,
            @NotNull String slug,
            @NotNull String title,
            @Nullable String description,
            @Nullable String iconUrl,
            @NotNull String pageUrl,
            long downloads) {}

    /**
     * Plugins matching {@code query} that are tagged for {@code gameVersion} on {@code loader}.
     *
     * <h2>Filtered, never merely sorted</h2>
     * The facets are the whole point of searching here rather than on the project's own site: a
     * list somebody can install from must not contain a plugin that cannot run on this network.
     * Measured against the live API on 2026-09-19, {@code categories:velocity} with
     * {@code versions:26.2} answers 310 projects and {@code categories:paper} answers a far larger
     * set - so the fallback the ticket describes, narrowing the proxy's search to its own loader
     * and accepting a shorter list, is simply what this does for both platforms.
     *
     * <p><b>{@code categories}, not {@code loaders}.</b> The two filters are spelled differently on
     * the two endpoints - a version query takes {@code loaders}, a search takes the loader as a
     * {@code categories} facet - and that asymmetry is Modrinth's own. Getting it wrong is not an
     * error: the facet simply matches nothing and the search comes back empty, which reads exactly
     * like a plugin nobody has written.</p>
     *
     * <p>A hit is <b>not</b> a promise that the plugin can be installed. The search index answers
     * per project; {@link #newest} answers per version and is what decides, which is why adding a
     * plugin asks it rather than trusting the hit.</p>
     *
     * @param query       what was typed. Blank is allowed and means "the most popular ones", which
     *                    is what an empty search box should show rather than nothing
     * @param gameVersion the Minecraft version this service runs
     * @param loader      {@code paper} or {@code velocity} - {@code Topology.Kind#modrinthLoader}
     * @throws IOException if the API could not be read
     */
    public @NotNull List<Hit> search(
            final @NotNull String query, final @NotNull String gameVersion, final @NotNull String loader)
            throws IOException {
        // Modrinth's own shape: facets is a JSON array of arrays, AND between the outer entries,
        // OR inside each. So this reads "on this loader, AND for this Minecraft version, AND a
        // plugin" - three separate requirements rather than three alternatives.
        final String facets =
                "[[\"categories:" + loader + "\"],[\"versions:" + gameVersion + "\"],[\"project_type:plugin\"]]";
        final URI uri = URI.create(
                SEARCH + "?query=" + encode(query.strip()) + "&limit=" + SEARCH_LIMIT + "&facets=" + encode(facets));

        final String what = "Modrinth search for \"" + query.strip() + "\" on " + loader + "/" + gameVersion;
        final JsonObject answer = Json.object(http.get(uri), what);
        final JsonElement hits = answer.get("hits");
        if (hits == null || !hits.isJsonArray()) {
            throw new IOException(what + ": the answer carries no \"hits\" array.");
        }

        final List<Hit> found = new ArrayList<>();
        for (final JsonElement element : hits.getAsJsonArray()) {
            final JsonObject hit = element.getAsJsonObject();
            final String projectId = Json.optionalString(hit, "project_id");
            final String slug = Json.optionalString(hit, "slug");
            if (projectId == null || slug == null) {
                // Skipped rather than refused: one odd row out of twenty must not cost the other
                // nineteen their search, and a hit with no id is one nothing could be installed
                // from anyway.
                continue;
            }
            found.add(new Hit(
                    projectId,
                    slug,
                    java.util.Objects.requireNonNullElse(Json.optionalString(hit, "title"), slug),
                    Json.optionalString(hit, "description"),
                    Json.optionalString(hit, "icon_url"),
                    PAGE + slug,
                    Json.number(hit, "downloads", 0L)));
        }
        return List.copyOf(found);
    }

    // ---------------------------------------------------------------- what a jar is

    /**
     * A Modrinth project as the plugin list draws it.
     *
     * @param iconUrl as Modrinth states it; the caller decides whether a browser may load it
     */
    public record Project(
            @NotNull String projectId,
            @NotNull String slug,
            @NotNull String title,
            @Nullable String iconUrl,
            @NotNull String pageUrl) {}

    /**
     * The project a file with this SHA-512 was published under, or {@code null} when Modrinth has
     * never published that file.
     *
     * <p>The hash is the identity, not the name: {@code voicechat-bukkit-2.6.24.jar} says nothing about
     * which project it came from, and the same answer holds for a jar somebody put there by hand. A
     * 404 is an answer - "not from Modrinth" - and every other failure is a failure.</p>
     */
    public @Nullable String projectOfFile(final @NotNull String sha512) throws IOException {
        final URI uri = URI.create(VERSION_FILE + sha512 + "?algorithm=sha512");
        final String body;
        try {
            body = http.get(uri);
        } catch (final HttpException answered) {
            if (answered.status() == 404) {
                return null;
            }
            throw answered;
        }
        return Json.string(Json.object(body, "Modrinth version_file"), "project_id", "Modrinth version_file");
    }

    /** Name, slug and icon of each of {@code projectIds}, in one request. Unknown ids are left out. */
    public @NotNull List<Project> projects(final @NotNull Collection<String> projectIds) throws IOException {
        if (projectIds.isEmpty()) {
            return List.of();
        }
        final String ids = projectIds.stream()
                .map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        final JsonArray answer =
                Json.array(http.get(URI.create(PROJECTS + "?ids=" + encode(ids))), "Modrinth projects");
        final List<Project> found = new ArrayList<>();
        for (final JsonElement element : answer) {
            final JsonObject project = element.getAsJsonObject();
            final String id = Json.optionalString(project, "id");
            final String slug = Json.optionalString(project, "slug");
            if (id == null || slug == null) {
                continue;
            }
            found.add(new Project(
                    id,
                    slug,
                    java.util.Objects.requireNonNullElse(Json.optionalString(project, "title"), slug),
                    Json.optionalString(project, "icon_url"),
                    PAGE + slug));
        }
        return List.copyOf(found);
    }

    private static @Nullable JsonObject primaryFile(final @NotNull JsonObject version) {
        final JsonElement files = version.get("files");
        if (files == null || !files.isJsonArray()) {
            return null;
        }
        for (final JsonElement element : files.getAsJsonArray()) {
            final JsonObject file = element.getAsJsonObject();
            if (Json.bool(file, "primary", false)) {
                return file;
            }
        }
        return null;
    }

    private static @NotNull Instant published(final @NotNull JsonObject version) {
        final String raw = Json.optionalString(version, "date_published");
        if (raw == null) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(raw);
        } catch (final DateTimeParseException unparseable) {
            return Instant.EPOCH;
        }
    }

    private static @NotNull String encode(final @NotNull String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
