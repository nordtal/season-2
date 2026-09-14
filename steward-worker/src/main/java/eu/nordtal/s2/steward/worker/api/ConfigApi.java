package eu.nordtal.s2.steward.worker.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import eu.nordtal.s2.steward.worker.configfile.ConfigChange;
import eu.nordtal.s2.steward.worker.configfile.ConfigDocument;
import eu.nordtal.s2.steward.worker.configfile.ConfigEntry;
import eu.nordtal.s2.steward.worker.configfile.ConfigFiles;
import eu.nordtal.s2.steward.worker.configfile.ConfigLocation;
import eu.nordtal.s2.steward.worker.configfile.StaleConfigException;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.ConflictResponse;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.InternalServerErrorResponse;
import io.javalin.http.NotFoundResponse;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The three routes over {@code eu.nordtal.s2.steward.worker.configfile}.
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
public final class ConfigApi {

    private static final Logger log = LoggerFactory.getLogger(ConfigApi.class);

    private final Path root;

    public ConfigApi(final @NotNull Path root) {
        this.root = root;
    }

    /** {@code GET /api/config} - every file under the mount, without reading any of them. */
    public void list(final @NotNull Context ctx) {
        ctx.json(locations().stream().map(ConfigApi::describe).toList());
    }

    /** {@code GET /api/config/<file>} - one file, as a form. */
    public void one(final @NotNull Context ctx) {
        final ConfigLocation location = locate(ctx);
        ctx.json(document(location, read(location)));
    }

    /**
     * {@code PUT /api/config/<file>} - apply changes and answer with the file as it now reads.
     *
     * <p>The body is {@code {"revision": "…", "changes": {"path": "value", "other.path": ["a"]}}}.
     * A string is a scalar and an array is a list, and the two are not interchangeable:
     * {@link ConfigFiles} refuses a shape that does not match the key, which is what stops a list
     * of three services being replaced by the word "smp".</p>
     *
     * <p><b>{@code revision} is what the GET above handed out</b>, and it is required. A form is
     * open for as long as somebody is reading the comments in the file, and two admins on one file
     * is an ordinary evening rather than a rare race. Without it the later save would apply its
     * changes to whatever it found and write the result - the earlier admin's change would not
     * conflict, it would simply be gone, with nothing anywhere saying so. A stale one is a 409 and
     * the page shows the file as it now stands.</p>
     */
    public void save(final @NotNull Context ctx) {
        final ConfigLocation location = locate(ctx);
        if (!location.writable()) {
            throw new ForbiddenResponse(location.name() + " is mounted read-only in this container,"
                    + " so this interface cannot save a change to it.");
        }
        final JsonObject body = bodyOf(ctx.body());
        final Map<String, ConfigChange> changes = changesOf(body);
        final String revision = revisionOf(body);
        try {
            ctx.json(document(location, ConfigFiles.write(location.file(), changes, revision)));
        } catch (final StaleConfigException e) {
            // Nobody made a mistake and the change needs no correcting: somebody was faster. The
            // page redraws from the file as it now stands and the operator decides again.
            log.info("{} was not saved: it was written since it was read ({} -> {})",
                    location.file(), e.expected(), e.actual());
            // The sentence says what this answer is, and not what it carries: a ConflictResponse is
            // a message, not a document. The page re-reads the file when it sees the 409 (queries
            // .ts invalidates on that status alone); promising the current file in the body of the
            // refusal would be a promise a client could believe and then draw a stale form from.
            throw new ConflictResponse(location.name() + " was changed by somebody else while this"
                    + " form was open, so nothing was saved. Read it again and make the change"
                    + " once more if it is still the one you want.");
        } catch (final IllegalArgumentException e) {
            // The operator asked for something the file cannot be given: a key that is not there, a
            // value of the wrong type, a list sent to a single value. Their mistake, their sentence.
            throw new BadRequestResponse(e.getMessage());
        } catch (final IOException e) {
            // Javalin does not log a handled HttpResponseException, so without this line the only
            // trace of a full disk or a read-only mount is one sentence in somebody's browser.
            log.error("{} could not be written", location.file(), e);
            throw new InternalServerErrorResponse(location.name() + " could not be written: "
                    + e.getMessage());
        } catch (final IllegalStateException e) {
            // ConfigFiles refused to write what it had rendered, because it would not read back as
            // what was asked for. That is this program's bug and not the operator's, so it stays a
            // 500 - but it is the one failure that most needs a stack trace on this side.
            log.error("{} was not written: the rendered file would not read back", location.file(), e);
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
        // BOTH, and in that order, because they fail differently. A file that cannot be read has
        // nothing to show; one that can be read but not written has a form to look at and no save
        // button. The listing used to send only `writable`, so an unreadable file was drawn as an
        // ordinary greyed-out row and said what was wrong only when somebody tapped it.
        row.put("readable", location.readable());
        row.put("writable", location.writable());
        return row;
    }

    private ConfigDocument read(final ConfigLocation location) {
        // NOT PERMITTED IS NOT A BAD REQUEST, and it used to be: every IOException became a 400,
        // so a file this process may not open answered the browser with its own path and the words
        // "Permission denied" under a red alert about the request. The request was fine. The
        // deployment was not, and that is a different sentence with a different thing to do about
        // it - which is why it is asked BEFORE the open rather than sorted out of the exception
        // afterwards by reading its message.
        if (!location.readable()) {
            log.warn("{} cannot be read by this process", location.file());
            throw new InternalServerErrorResponse(location.name() + " is on this host but this"
                    + " service may not open it. Nothing is wrong with what you asked for: the file"
                    + " belongs to another user, so the mount that would let Steward read it is"
                    + " missing or the file's permissions changed.");
        }
        try {
            return ConfigFiles.read(location.file());
        } catch (final AccessDeniedException denied) {
            // The same thing again, caught rather than asked - because a permission can change
            // between the two lines, and because a directory somewhere above this file can refuse
            // the open without `isReadable` on the file itself saying so.
            log.warn("{} cannot be read by this process", location.file(), denied);
            throw new InternalServerErrorResponse(location.name() + " is on this host but this"
                    + " service may not open it.");
        } catch (final IOException e) {
            // What is left IS about the request, or rather about the file it names: it is not
            // YAML, and the message says which line. That is the case this branch was written for.
            throw new BadRequestResponse(e.getMessage());
        }
    }

    private static Map<String, Object> document(final ConfigLocation location,
                                                final ConfigDocument read) {
        final Map<String, Object> answer = new LinkedHashMap<>(describe(location));
        answer.put("revision", read.revision());
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

    private static JsonObject bodyOf(final String body) {
        try {
            return JsonParser.parseString(body == null ? "" : body).getAsJsonObject();
        } catch (final JsonSyntaxException | IllegalStateException | IllegalArgumentException e) {
            throw new BadRequestResponse("The body has to be a JSON object with `revision` and"
                    + " `changes` fields.");
        }
    }

    /**
     * The revision the browser was last shown.
     *
     * <p>Required, and deliberately not optional-with-a-default: a save that may omit it is a save
     * every client can accidentally make unconditional, and the one that forgets is the one that
     * quietly overwrites somebody. A caller that genuinely wants to write over whatever is there
     * reads the file first - which takes one request and means they have seen it.</p>
     */
    private static String revisionOf(final JsonObject body) {
        final JsonElement revision = body.get("revision");
        if (revision == null || !revision.isJsonPrimitive() || revision.getAsString().isBlank()) {
            throw new BadRequestResponse("`revision` has to be the value this file was last read"
                    + " with, so that a change somebody else made in the meantime is not"
                    + " overwritten.");
        }
        return revision.getAsString();
    }

    private static Map<String, ConfigChange> changesOf(final JsonObject asked) {
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
    public Optional<String> whatIsMissing() {
        return locations().isEmpty()
                ? Optional.of("Nothing is mounted at " + root)
                : Optional.empty();
    }
}
