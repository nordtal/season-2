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
 * The Modrinth v2 API, for the two third-party plugins the SMP server requires: PacketEvents and
 * Chunky.
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
 * <h2>Only {@code release}</h2>
 * {@code version_type} is one of {@code release}, {@code beta}, {@code alpha}. Only the first is
 * considered. An updater that pulls somebody's alpha onto a server people paid to play on, at
 * three in the morning, without being asked, is the thing this whole module is arranged to avoid.
 */
public final class Modrinth {

    private static final String API = "https://api.modrinth.com/v2/project/";

    private final Http http;

    public Modrinth(final Http http) {
        this.http = http;
    }

    /**
     * The newest {@code release} of {@code projectId} tagged for {@code gameVersion} on
     * {@code loader}.
     *
     * @param artifact the id this module knows the plugin by, carried into the {@link RemoteFile}.
     * @throws IOException if the filter matches nothing, or if the newest match has no primary
     *                     file. Both are refusals rather than fallbacks: "no version for 26.2"
     *                     means the plugin has not been updated for the platform yet, and
     *                     installing the 26.1 build instead is not a decision a program gets to
     *                     make.
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

        final List<JsonObject> releases = new ArrayList<>();
        for (final JsonElement element : versions) {
            final JsonObject version = element.getAsJsonObject();
            if ("release".equals(Json.optionalString(version, "version_type"))) {
                releases.add(version);
            }
        }

        if (releases.isEmpty()) {
            throw new IOException(what + ": no stable release is tagged for this platform. Either"
                    + " the plugin has not been updated for it yet, or a pre-release is being"
                    + " waited on - neither is something this module may work around by installing"
                    + " a build for a different Minecraft version.");
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
