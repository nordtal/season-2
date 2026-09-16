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
import eu.nordtal.s2.steward.worker.configfile.RawSyntax;
import eu.nordtal.s2.steward.worker.configfile.StaleConfigException;
import eu.nordtal.s2.steward.worker.docker.DockerException;
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
 *
 * <p><b>A save also asks the affected service to pick the change up (steward/59).</b> Till's
 * complaint that started that ticket: a change that needs a second click on a second button is a
 * change that is not applied yet, and the interface used to leave it there. See {@link #reload}
 * for what "ask" means and {@link #RELOAD_COMMAND} for which files it actually reaches - never a
 * restart, which stays a deliberate click of Till's own.</p>
 */
public final class ConfigApi {

    private static final Logger log = LoggerFactory.getLogger(ConfigApi.class);

    /**
     * One line into a running server's console, with nothing read back - exactly
     * {@link eu.nordtal.s2.steward.worker.docker.Console#send}, narrowed to the one method this
     * class needs so a test can hand it a lambda instead of a real {@code Docker} socket.
     */
    @FunctionalInterface
    public interface ConsoleLine {
        void send(@NotNull String service, @NotNull String command);
    }

    /**
     * Which running service to poke, and with what line, once a save actually changes a file on
     * disk - keyed by the same identity {@link #locate} matches against, because reloadability is
     * a property of one file, not of a whole service. {@code smp/smp/config.yml} binds worlds and
     * borders once at enable and {@code /smp reload} deliberately never re-reads it (see
     * {@code ReloadSmp}, {@code SmpPlugin} in {@code :smp}); {@code smp/smp/milestones.yml},
     * {@code smp/smp/sounds.yml}, {@code smp/smp/colours.yml} and
     * {@code smp/smp/prestige-colours.yml} sit right beside it in the same service and are the files
     * that command actually re-reads. The same reading gives {@code hunger-games/hunger-games/sounds.yml}
     * (see {@code ReloadHungerGames}) - {@code config.yml} there is excluded on purpose too, because
     * a game already running must not have its border schedule move under it.
     *
     * <h2>Why a map here and not on the jcore schema</h2>
     * A {@code @ConfigSpec} already carries a key's type and comment (steward/54, steward/55); it
     * carries nothing about whether a change to it needs a restart, and jcore has no annotation for
     * that today. Adding one is a jcore change with a release of its own, and out of reach here
     * tonight regardless: {@code ConfigFiles.java}, which reads that schema, belongs to another
     * agent this same evening. This map is the honest stand-in - short on purpose, because being
     * wrong in either direction is a real failure: too eager sends a command nobody asked for into
     * a live server's console, too conservative tells an operator a restart is needed when a reload
     * would already have done it. It grows by hand exactly when a plugin gains or loses a reload
     * command, the same trade {@code Topology} already makes against {@code compose.yml} for the
     * same reason - the alternative is inferring live behaviour from a file nobody parses at
     * runtime.
     */
    private static final Map<String, String> RELOAD_COMMAND = Map.of(
            "smp/smp/milestones.yml", "smp reload",
            "smp/smp/sounds.yml", "smp reload",
            "smp/smp/colours.yml", "smp reload",
            "smp/smp/prestige-colours.yml", "smp reload",
            "hunger-games/hunger-games/sounds.yml", "hg reload");

    private final Path root;
    private final ConsoleLine console;

    public ConfigApi(final @NotNull Path root, final @NotNull ConsoleLine console) {
        this.root = root;
        this.console = console;
    }

    /** {@code GET /api/config} - every file under the mount, without reading any of them. */
    public void list(final @NotNull Context ctx) {
        ctx.json(locations().stream().map(ConfigApi::describe).toList());
    }

    /**
     * {@code GET /api/config/<file>} - one file, as a form, or as raw text (steward/56).
     *
     * <p>{@code discover()} (steward/55) no longer looks at the extension, so this route is now
     * asked about files that were never YAML to begin with - a plugin's {@code README.txt}, a
     * {@code voicechat-server.properties}. Whatever will not parse as a config file - one of those,
     * or an ordinary {@code .yml} with a mistake in it - answers with 200 and the file's own bytes
     * under {@code raw: true} rather than a 400: this route's job is to show what is on disk, and a
     * file that cannot be split into keys can still be shown, just not as a form.</p>
     */
    public void one(final @NotNull Context ctx) {
        final ConfigLocation location = locate(ctx);
        ctx.json(read(location));
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
            final Map<String, Object> answer = document(location,
                    ConfigFiles.write(location.file(), changes, revision));
            // The write above is what makes the change real; this is what makes it reach anything.
            // One request, one click - never a second button for "now actually use it" (steward/59).
            answer.put("reload", reload(location));
            ctx.json(answer);
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

    /**
     * {@code PUT /api/config-raw/<file>} - saves exactly the text typed into the raw editor
     * (steward/60).
     *
     * <p>The body is {@code {"revision": "…", "content": "…"}} - no {@code changes} map, because
     * there is no shape here to check a change against: the raw editor exists for a file this class
     * could not split into keys at all, or one an operator wants to hand-edit byte for byte anyway.
     * {@link ConfigFiles#writeRaw} writes exactly what it is given.</p>
     *
     * <p><b>Nothing here is ever refused for what the text says.</b> {@link RawSyntax#check} looks
     * at the content against the format its file name implies and, when it finds something, that
     * becomes a warning in the response - never a {@code 400}, and never something that stops the
     * write below from happening. Till's decision for this editor is that one which refuses to save
     * a file is one an operator has to work around. The only refusals left are the ones
     * {@link #save} already has for reasons that have nothing to do with syntax: the mount is
     * read-only, or somebody else wrote the file since this editor read it.</p>
     */
    public void saveRaw(final @NotNull Context ctx) {
        final ConfigLocation location = locate(ctx);
        if (!location.writable()) {
            throw new ForbiddenResponse(location.name() + " is mounted read-only in this container,"
                    + " so this interface cannot save a change to it.");
        }
        final JsonObject body = bodyOf(ctx.body());
        final String content = contentOf(body);
        final String revision = revisionOf(body);
        final List<String> warnings = RawSyntax.check(location.name(), content)
                .map(warning -> List.of(warning.sentence()))
                .orElse(List.of());
        try {
            final String newRevision = ConfigFiles.writeRaw(location.file(), content, revision);
            final Map<String, Object> answer = new LinkedHashMap<>(describe(location));
            answer.put("raw", true);
            answer.put("revision", newRevision);
            answer.put("content", content);
            answer.put("warnings", warnings);
            ctx.json(answer);
        } catch (final StaleConfigException e) {
            // Exactly #save's own reasoning: nobody made a mistake, somebody else was faster.
            log.info("{} was not saved: it was written since it was read ({} -> {})",
                    location.file(), e.expected(), e.actual());
            throw new ConflictResponse(location.name() + " was changed by somebody else while this"
                    + " editor was open, so nothing was saved. Read it again and make the change"
                    + " once more if it is still the one you want.");
        } catch (final IOException e) {
            log.error("{} could not be written", location.file(), e);
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

    private Map<String, Object> read(final ConfigLocation location) {
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
            return document(location, ConfigFiles.read(location.file()));
        } catch (final AccessDeniedException denied) {
            // The same thing again, caught rather than asked - because a permission can change
            // between the two lines, and because a directory somewhere above this file can refuse
            // the open without `isReadable` on the file itself saying so.
            log.warn("{} cannot be read by this process", location.file(), denied);
            throw new InternalServerErrorResponse(location.name() + " is on this host but this"
                    + " service may not open it.");
        } catch (final IOException e) {
            // Not a config file this class can split into keys - a foreign file steward/55's
            // broadened discover() now surfaces (a plugin's README, a .properties file), or a .yml
            // with a mistake in it. Either way there is still something to show: the bytes on disk,
            // read-only, rather than a 400 with a path and a line number in it (steward/56).
            log.info("{} does not read as a config file; showing it as raw text: {}",
                    location.file(), e.getMessage());
            return rawDocument(location, e.getMessage());
        }
    }

    // Package-private: ConfigApiReloadTest constructs a ConfigDocument directly, without a
    // Javalin context or a file on disk, to check what restartRequired says.
    static Map<String, Object> document(final ConfigLocation location,
                                                final ConfigDocument read) {
        final Map<String, Object> answer = new LinkedHashMap<>(describe(location));
        answer.put("revision", read.revision());
        answer.put("header", read.header());
        // Shown at the file, not only after a save (steward/59's third case): a setting nothing
        // reloads says so the moment the form is open, not only in the toast the save produces.
        answer.put("restartRequired", !RELOAD_COMMAND.containsKey(identityOf(location)));
        final List<Map<String, Object>> entries = new ArrayList<>(read.entries().size());
        for (final ConfigEntry entry : read.entries()) {
            entries.add(describe(entry));
        }
        answer.put("entries", entries);
        return answer;
    }

    /**
     * Asks the affected service to pick a just-written change up, and says in one word plus one
     * sentence what happened - the three outcomes steward/59 asks not to look alike.
     *
     * <p>{@code RESTART_REQUIRED} when nothing in {@link #RELOAD_COMMAND} names this file: no
     * command is sent, because there is nothing this process could send that this file's own
     * reload command would read. {@code APPLIED} when the line was handed to a running container's
     * console - {@link ConsoleLine#send} throwing nothing back only means the exec succeeded, not
     * that the plugin liked what it read; a malformed file the plugin refuses is reported on that
     * service's own console, which is the log every admin here is already watching, the same way a
     * console command's own reply always has been. {@code NO_ANSWER} when the container that would
     * have run it is not there to ask - a service that is down, mid-restart, or never started.
     * Neither branch restarts anything: that stays Till's own click, on purpose.</p>
     */
    // Package-private for the same reason: ConfigApiReloadTest drives the three outcomes with a
    // fake ConsoleLine, never a real Docker socket.
    Map<String, Object> reload(final ConfigLocation location) {
        final Map<String, Object> answer = new LinkedHashMap<>();
        final String command = RELOAD_COMMAND.get(identityOf(location));
        if (command == null) {
            answer.put("status", "RESTART_REQUIRED");
            answer.put("message", "Saved. Nothing reloads " + location.name() + " live; "
                    + (location.service().isEmpty() ? "it" : location.service())
                    + " only reads it again at its next restart, which stays a click of its own.");
            return answer;
        }
        try {
            console.send(location.service(), command);
            answer.put("status", "APPLIED");
            answer.put("message", "Saved, and \"" + command + "\" was sent to "
                    + location.service() + "'s console to pick it up. Its reply, if the change was"
                    + " refused, appears in that service's own log.");
        } catch (final DockerException e) {
            log.warn("{} was saved but {} could not be reached to reload it: {}",
                    location.file(), location.service(), e.getMessage());
            answer.put("status", "NO_ANSWER");
            answer.put("message", "Saved, but " + location.service() + " did not answer: "
                    + e.getMessage() + ". The change is on disk and takes effect once that service"
                    + " is running again.");
        } catch (final IllegalArgumentException e) {
            // Cannot happen for anything RELOAD_COMMAND names today - every key in it belongs to
            // one of the four services with a console - but a service losing its console without
            // this map being updated to match should read as "needs a restart", not crash the save
            // that already succeeded.
            log.warn("{} names a reload command for {}, which refused it: {}",
                    location.file(), location.service(), e.getMessage());
            answer.put("status", "RESTART_REQUIRED");
            answer.put("message", "Saved. " + e.getMessage());
        }
        return answer;
    }

    /**
     * A file that could not be read as YAML, shown as itself instead of as a 400 (steward/56) - and,
     * since steward/60, editable as itself too.
     *
     * <p>{@code raw: true} is still the marker that says "nothing here was parsed into keys", but
     * {@code revision} is no longer absent the way steward/56 originally left it: {@link #saveRaw}
     * needs the same stale-write guard {@link #save} already has, and {@link ConfigFiles#revisionOf}
     * is computed the same way for any text regardless of whether this class could split it into
     * keys - so a raw document now carries one too, of the content string read below.</p>
     */
    private static Map<String, Object> rawDocument(final ConfigLocation location, final String reason) {
        final Map<String, Object> answer = new LinkedHashMap<>(describe(location));
        answer.put("raw", true);
        answer.put("reason", reason);
        try {
            final String content = java.nio.file.Files.readString(location.file(),
                    java.nio.charset.StandardCharsets.UTF_8);
            answer.put("content", content);
            answer.put("revision", ConfigFiles.revisionOf(content));
        } catch (final IOException e) {
            log.warn("{} could not be read as raw text either", location.file(), e);
            throw new InternalServerErrorResponse(location.name() + " could not be read: "
                    + e.getMessage());
        }
        return answer;
    }

    private static Map<String, Object> describe(final ConfigEntry entry) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("path", entry.path());
        row.put("key", entry.key());
        row.put("label", entry.label());
        row.put("comments", entry.comments());
        // The schema's own text (steward/55, steward/56): `explanation` is empty and
        // `noExplanationNeeded` is false for a key no schema covers, which the interface then draws
        // exactly as it always drew a key with no comment.
        row.put("explanation", entry.explanation());
        row.put("noExplanationNeeded", entry.noExplanationNeeded());
        // `filled` is what a secret is allowed to say about itself. It is sent for every key, not
        // only the secret ones, so the page has one rule to draw rather than two.
        row.put("filled", !entry.value().isEmpty() || !entry.items().isEmpty()
                || !entry.sections().isEmpty());
        if (!entry.secret()) {
            row.put("value", entry.value());
            row.put("items", entry.items());
        }
        row.put("kind", entry.kind().name());
        row.put("type", entry.type().name());
        row.put("line", entry.line());
        row.put("editable", entry.editable());
        row.put("secret", entry.secret());
        row.put("inSchema", entry.inSchema());
        if (entry.choices() != null) {
            final Map<String, Object> choices = new LinkedHashMap<>();
            choices.put("values", entry.choices().values());
            choices.put("strict", entry.choices().strict());
            row.put("choices", choices);
        }
        // template and sections exist only for a SECTIONS entry (steward/68) - api.ts's ConfigEntry
        // declares both undefined for every other kind, which is what leaving the key out of the
        // map achieves, rather than sending an empty array a scalar or a section itself never has.
        if (entry.kind() == ConfigEntry.Kind.SECTIONS) {
            if (!entry.template().isEmpty()) {
                row.put("template", entry.template().stream().map(ConfigApi::describe).toList());
            }
            row.put("sections", entry.sections().stream()
                    .map(section -> section.stream().map(ConfigApi::describe).toList())
                    .toList());
        }
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

    /**
     * The raw text a {@link #saveRaw} body carries under {@code content} - required, but an empty
     * string is a perfectly good value: an operator emptying a file on purpose is not a malformed
     * request.
     */
    private static String contentOf(final JsonObject body) {
        final JsonElement content = body.get("content");
        if (content == null || !content.isJsonPrimitive()) {
            throw new BadRequestResponse("`content` has to be the text to write, as a string.");
        }
        return content.getAsString();
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
            if (!array.isEmpty() && array.get(0).isJsonObject()) {
                return ConfigChange.sections(sectionsOf(path, array));
            }
            final List<String> items = new ArrayList<>(array.size());
            for (final JsonElement item : array) {
                items.add(textOf(path, item));
            }
            return ConfigChange.list(items);
        }
        return ConfigChange.of(textOf(path, value));
    }

    /**
     * The entries of a {@link ConfigEntry.Kind#SECTIONS} change - an array whose first element is a
     * JSON object rather than a scalar (steward/68). Every element has to follow the same shape;
     * a mix is reported rather than silently coerced, since {@link ConfigFiles} has no way to tell
     * whether a stray scalar there was meant as a whole new entry or a mistake.
     */
    private static List<Map<String, String>> sectionsOf(final String path, final JsonArray array) {
        final List<Map<String, String>> sections = new ArrayList<>(array.size());
        for (final JsonElement item : array) {
            if (!item.isJsonObject()) {
                throw new BadRequestResponse(path + ": every entry of a list of sections has to be"
                        + " an object of field to new value, not " + item);
            }
            final Map<String, String> fields = new LinkedHashMap<>();
            for (final Map.Entry<String, JsonElement> field : item.getAsJsonObject().entrySet()) {
                fields.put(field.getKey(), textOf(path + "." + field.getKey(), field.getValue()));
            }
            sections.add(fields);
        }
        return sections;
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
