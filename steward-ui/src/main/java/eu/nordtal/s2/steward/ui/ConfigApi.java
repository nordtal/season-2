package eu.nordtal.s2.steward.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import eu.nordtal.s2.steward.ui.configfile.ConfigChange;
import eu.nordtal.s2.steward.ui.configfile.ConfigDocument;
import eu.nordtal.s2.steward.ui.configfile.ConfigEntry;
import eu.nordtal.s2.steward.ui.configfile.ConfigFiles;
import eu.nordtal.s2.steward.ui.configfile.ConfigLocation;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.InternalServerErrorResponse;
import io.javalin.http.NotFoundResponse;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The three routes over {@code eu.nordtal.s2.steward.ui.configfile}.
 *
 * <p><b>A file is found by matching, never by joining.</b> What the browser sends is compared with
 * the list of files actually discovered under the mount; nothing builds a path out of it. That is
 * what makes {@code ../../etc/shadow} a 404 rather than a question about how many times the string
 * was decoded on the way here - and it stays true however clever the encoding gets, because the
 * string is never used as a path at all.</p>
 *
 * <p><b>A secret never leaves this process.</b> Till's decision, 2026-09-13: a key whose name says
 * credential - the bot token, the bunq key, the worker secret - is sent as {@code filled: true}
 * and no value. It can still be overwritten, because typing a new one does not require having seen
 * the old one. What is given up is comparing two services' tokens by eye; what is bought is that
 * the Discord bot token is not in a browser cache, a screen recording or the next XSS.</p>
 */
final class ConfigApi {

    private final Path root;

    ConfigApi(final @NotNull Path root) {
        this.root = root;
    }

    /** {@code GET /api/config} - every file under the mount, without reading any of them. */
    void list(final @NotNull Context ctx) {
        ctx.json(locations().stream().map(ConfigApi::describe).toList());
    }

    /** {@code GET /api/config/<file>} - one file, as a form. */
    void one(final @NotNull Context ctx) {
        final ConfigLocation location = locate(ctx);
        ctx.json(document(location, read(location)));
    }

    /**
     * {@code PUT /api/config/<file>} - apply changes and answer with the file as it now reads.
     *
     * <p>The body is {@code {"changes": {"path": "value", "other.path": ["a", "b"]}}}. A string is
     * a scalar and an array is a list, and the two are not interchangeable: {@link ConfigFiles}
     * refuses a shape that does not match the key, which is what stops a list of three services
     * being replaced by the word "smp".</p>
     */
    void save(final @NotNull Context ctx) {
        final ConfigLocation location = locate(ctx);
        if (!location.writable()) {
            throw new ForbiddenResponse(location.name() + " is mounted read-only in this container,"
                    + " so this interface cannot save a change to it.");
        }
        final Map<String, ConfigChange> changes = changesOf(ctx.body());
        try {
            ctx.json(document(location, ConfigFiles.write(location.file(), changes)));
        } catch (final IllegalArgumentException e) {
            // The operator asked for something the file cannot be given: a key that is not there, a
            // value of the wrong type, a list sent to a single value. Their mistake, their sentence.
            throw new BadRequestResponse(e.getMessage());
        } catch (final IOException e) {
            throw new InternalServerErrorResponse(location.name() + " could not be written: "
                    + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------
    // Finding the file
    // ---------------------------------------------------------------------------------------

    private List<ConfigLocation> locations() {
        try {
            return ConfigFiles.discover(root);
        } catch (final UncheckedIOException e) {
            throw new InternalServerErrorResponse("The config mount at " + root
                    + " could not be listed: " + e.getMessage());
        }
    }

    /**
     * The file the request is about, or a 404.
     *
     * <p>{@code <file>} is the path under the mount as the listing reported it - so
     * {@code steward-worker/steward.yml}, and {@code steward.yml} for a file lying directly in the
     * root with no service directory above it. Javalin has already decoded it once; it is compared,
     * not resolved, so once more would make no difference either.</p>
     */
    private ConfigLocation locate(final Context ctx) {
        final String asked = ctx.pathParam("file");
        return locations().stream()
                .filter(location -> identityOf(location).equals(asked))
                .findFirst()
                .orElseThrow(() -> new NotFoundResponse(
                        "There is no config file called " + asked + " under " + root + "."));
    }

    /** How a file is named in a URL: what {@code discover} found, service directory included. */
    private static String identityOf(final ConfigLocation location) {
        return location.service().isEmpty()
                ? location.name()
                : location.service() + "/" + location.name();
    }

    // ---------------------------------------------------------------------------------------
    // What goes over the wire
    // ---------------------------------------------------------------------------------------

    private static Map<String, Object> describe(final ConfigLocation location) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("service", location.service());
        row.put("name", location.name());
        row.put("path", identityOf(location));
        row.put("writable", location.writable());
        return row;
    }

    private ConfigDocument read(final ConfigLocation location) {
        try {
            return ConfigFiles.read(location.file());
        } catch (final IOException e) {
            // A file that is not YAML is not a server fault, and the message names the line.
            throw new BadRequestResponse(e.getMessage());
        }
    }

    private static Map<String, Object> document(final ConfigLocation location,
                                                final ConfigDocument read) {
        final Map<String, Object> answer = new LinkedHashMap<>(describe(location));
        answer.put("header", read.header());
        final List<Map<String, Object>> entries = new ArrayList<>(read.entries().size());
        for (final ConfigEntry entry : read.entries()) {
            entries.add(describe(entry));
        }
        answer.put("entries", entries);
        return answer;
    }

    private static Map<String, Object> describe(final ConfigEntry entry) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("path", entry.path());
        row.put("key", entry.key());
        row.put("label", entry.label());
        row.put("comments", entry.comments());
        // `filled` is what a secret is allowed to say about itself. It is sent for every key, not
        // only the secret ones, so the page has one rule to draw rather than two.
        row.put("filled", !entry.value().isEmpty() || !entry.items().isEmpty());
        if (!entry.secret()) {
            row.put("value", entry.value());
            row.put("items", entry.items());
        }
        row.put("kind", entry.kind().name());
        row.put("type", entry.type().name());
        row.put("line", entry.line());
        row.put("editable", entry.editable());
        row.put("secret", entry.secret());
        return row;
    }

    // ---------------------------------------------------------------------------------------
    // What comes in
    // ---------------------------------------------------------------------------------------

    private static Map<String, ConfigChange> changesOf(final String body) {
        final JsonObject asked;
        try {
            asked = JsonParser.parseString(body == null ? "" : body).getAsJsonObject();
        } catch (final JsonSyntaxException | IllegalStateException | IllegalArgumentException e) {
            throw new BadRequestResponse("The body has to be a JSON object with a `changes` field.");
        }
        final JsonElement changes = asked.get("changes");
        if (changes == null || !changes.isJsonObject()) {
            throw new BadRequestResponse("`changes` has to be an object of setting to new value.");
        }

        final Map<String, ConfigChange> answer = new LinkedHashMap<>();
        for (final Map.Entry<String, JsonElement> change : changes.getAsJsonObject().entrySet()) {
            answer.put(change.getKey(), changeOf(change.getKey(), change.getValue()));
        }
        if (answer.isEmpty()) {
            throw new BadRequestResponse("`changes` is empty - there is nothing to save.");
        }
        return answer;
    }

    private static ConfigChange changeOf(final String path, final JsonElement value) {
        if (value.isJsonArray()) {
            final JsonArray array = value.getAsJsonArray();
            final List<String> items = new ArrayList<>(array.size());
            for (final JsonElement item : array) {
                items.add(textOf(path, item));
            }
            return ConfigChange.list(items);
        }
        return ConfigChange.of(textOf(path, value));
    }

    /**
     * A value as text.
     *
     * <p>A number and a boolean are accepted and turned into their own characters, because a form
     * that sends {@code 8080} rather than {@code "8080"} is a form doing something reasonable.
     * {@link ConfigFiles} then decides whether the key can hold it.</p>
     */
    private static String textOf(final String path, final JsonElement value) {
        if (value.isJsonPrimitive()) {
            return value.getAsString();
        }
        if (value.isJsonNull()) {
            return "";
        }
        throw new BadRequestResponse(path + ": a setting is a value or a list of values, not "
                + value);
    }

    /** For the page that lists the mount: whether there is anything there at all. */
    Optional<String> whatIsMissing() {
        return locations().isEmpty()
                ? Optional.of("Nothing is mounted at " + root)
                : Optional.empty();
    }
}
