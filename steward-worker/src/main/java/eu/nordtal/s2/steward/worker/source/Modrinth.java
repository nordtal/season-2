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
import org.jspecify.annotations.Nullable;

/**
 * The Modrinth v2 API, for the third-party plugins the network runs, filtered by Minecraft version and loader.
 *
 * Only the primary file of a {@code release} counts, newest by {@code date_published}.
 */
public final class Modrinth {

    private static final String API = "https://api.modrinth.com/v2/project/";

    private static final String SEARCH = "https://api.modrinth.com/v2/search";

    /** The project's own page, linked next to a hit. */
    private static final String PAGE = "https://modrinth.com/plugin/";

    private static final String VERSION_FILE = "https://api.modrinth.com/v2/version_file/";
    private static final String PROJECTS = "https://api.modrinth.com/v2/projects";

    /** How many hits one search asks for. */
    public static final int SEARCH_LIMIT = 20;

    /**
     * The artefact ids for which a {@code beta} or an {@code alpha} counts: only {@code voicechat-velocity}.
     *
     * A named constant, not a setting; {@code ModrinthTest} holds it at exactly this one member.
     */
    public static final List<String> PRE_RELEASE_EXCEPTIONS = List.of("voicechat-velocity");

    /**
     * Modrinth answered, and it has no stable build of this plugin for this Minecraft version.
     *
     * Not an outage, so its service is not skipped; there is deliberately no fallback to another version.
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
     * The newest {@code release} of {@code projectId} tagged for {@code gameVersion} on {@code loader}.
     *
     * @param artifact the id this module knows the plugin by; a {@link #PRE_RELEASE_EXCEPTIONS} id also accepts betas
     * @throws Unsupported if the filter matches nothing
     * @throws IOException if the newest match has no primary file, or the API could not be read
     */
    public RemoteFile newest(
            final String artifact, final String projectId, final String gameVersion, final String loader)
            throws IOException {

        // Both filters are JSON arrays inside a query parameter, so brackets and quotes must survive encoding.
        final URI uri = URI.create(API + projectId + "/version"
                + "?game_versions=" + encode("[\"" + gameVersion + "\"]")
                + "&loaders=" + encode("[\"" + loader + "\"]"));

        final String what = "Modrinth " + artifact + " (" + projectId + ") for " + gameVersion + "/" + loader;
        final JsonArray versions = Json.array(http.get(uri), what);

        // Decided from the artefact id alone, so no caller or config file can point it elsewhere.
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

        // Newest first; a version with an unparseable date sorts last rather than crashing the run.
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

    /**
     * One hit of a plugin search, in the shape the interface draws it.
     *
     * @param projectId Modrinth's immutable id, which is the identity
     * @param slug the readable id, which becomes the artefact id of an added plugin
     * @param title the project's name
     * @param description the one-line summary Modrinth calls {@code description}
     * @param iconUrl the thumbnail on {@code cdn.modrinth.com}, or {@code null}; a CSP in front of steward-ui must
     *     allow it
     * @param pageUrl the project's own page, for the link next to the install button
     * @param downloads how many times it has been downloaded
     */
    public record Hit(
            String projectId,
            String slug,
            String title,
            @Nullable String description,
            @Nullable String iconUrl,
            String pageUrl,
            long downloads) {}

    /**
     * Plugins matching {@code query} that are tagged for {@code gameVersion} on {@code loader}.
     *
     * @param query what was typed; blank means the most popular ones
     * @param gameVersion the Minecraft version this service runs
     * @param loader {@code paper} or {@code velocity}, sent as a {@code categories} facet
     * @throws IOException if the API could not be read
     */
    public List<Hit> search(final String query, final String gameVersion, final String loader) throws IOException {
        // Facets are AND between the outer entries and OR inside each: three requirements here.
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
                // Skipped rather than refused, so one odd row costs no other result.
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

    /**
     * A Modrinth project as the plugin list draws it.
     *
     * @param iconUrl as Modrinth states it; the caller decides whether a browser may load it
     */
    public record Project(
            String projectId,
            String slug,
            String title,
            @Nullable String iconUrl,
            String pageUrl) {}

    /** The project a file with this SHA-512 was published under, or {@code null} when Modrinth never published it. */
    public @Nullable String projectOfFile(final String sha512) throws IOException {
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

    /** Name, slug and icon of each of {@code projectIds}, in one request; unknown ids are left out. */
    public List<Project> projects(final Collection<String> projectIds) throws IOException {
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

    private static @Nullable JsonObject primaryFile(final JsonObject version) {
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

    private static Instant published(final JsonObject version) {
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

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
