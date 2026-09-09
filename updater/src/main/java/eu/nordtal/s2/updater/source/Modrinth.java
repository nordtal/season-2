package eu.nordtal.s2.updater.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.s2.updater.http.Http;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;

/**
 * The Modrinth v2 API, for the third-party plugins the network runs: PacketEvents, Chunky,
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
 * considered. An updater that pulls somebody's alpha onto a server people paid to play on, at
 * three in the morning, without being asked, is the thing this whole module is arranged to avoid.
 *
 * <p>The rule is unchanged for every artefact but one. {@link #PRE_RELEASE_EXCEPTIONS} is that one,
 * written out by name rather than expressed as a switch - see its own note for why a setting would
 * have been the wrong shape.</p>
 */
public final class Modrinth {

    private static final String API = "https://api.modrinth.com/v2/project/";

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
     * per-artefact flag in {@code updater.yml}. That would put the decision in a deployed file
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
     * simply behind the platform read as a source the updater could not reach - which makes the
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
    public @NotNull RemoteFile newest(final @NotNull String artifact, final @NotNull String projectId,
                                      final @NotNull String gameVersion, final @NotNull String loader)
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
        releases.sort(Comparator.comparing((JsonObject version) -> published(version)).reversed());
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
