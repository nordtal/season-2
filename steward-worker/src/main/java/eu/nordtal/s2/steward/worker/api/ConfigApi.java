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
import eu.nordtal.s2.steward.worker.plan.Topology;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.ConflictResponse;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.InternalServerErrorResponse;
import io.javalin.http.NotFoundResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The three routes over {@code eu.nordtal.s2.steward.worker.configfile}.
 *
 * A file is found by matching, never by joining. What the browser sends is compared with the list of files actually
 * discovered under the mount; nothing builds a path out of it. That is what makes {@code ../../etc/shadow} a 404
 * rather than a question about how many times the string was decoded on the way here, because the string is never
 * used as a path at all.
 *
 * A secret never leaves this process: a key whose name says credential (the bot token, the bunq key, the worker
 * secret) is sent as {@code filled: true} and no value. It can still be overwritten, because typing a new one does
 * not require having seen the old one.
 *
 * A save also asks the affected service to pick the change up. A change that needs a second click on a second
 * button is a change that is not applied yet. See {@link #reload} for what "ask" means and {@link #RELOAD_COMMAND}
 * for which files it actually reaches; never a restart, which stays a deliberate click.
 */
public final class ConfigApi {

    private static final Logger log = LoggerFactory.getLogger(ConfigApi.class);

    /**
     * One line into a running server's console, with nothing read back.
     *
     * Exactly {@link eu.nordtal.s2.steward.worker.docker.Console#send}, narrowed to the one method this class
     * needs so a test can hand it a lambda instead of a real {@code Docker} socket.
     */
    @FunctionalInterface
    public interface ConsoleLine {
        void send(String service, String command);
    }

    /**
     * Which running service to poke, and with what line, once a save actually changes a file on disk.
     *
     * Keyed by the same identity {@link #locate} matches against, because reloadability is a property of one file,
     * not of a whole service. {@code smp/smp/config.yml} binds worlds and borders once at enable and
     * {@code /smp reload} deliberately never re-reads it; {@code smp/smp/milestones.yml}, {@code smp/smp/sounds.yml},
     * {@code smp/smp/colours.yml} and {@code smp/smp/prestige.yml} sit right beside it in the same service and are
     * the files that command actually re-reads. {@code hunger-games/hunger-games/sounds.yml} reads the same way;
     * {@code config.yml} there is excluded too, because a game already running must not have its border schedule
     * move under it.
     *
     * A {@code @ConfigSpec} carries a key's type and comment but nothing about whether a change to it needs a
     * restart, and jcore has no annotation for that. This map is the stand-in, kept short because being wrong in
     * either direction is a real failure: too eager sends a command nobody asked for into a live server's console,
     * too conservative tells an operator a restart is needed when a reload would already have done it. It grows by
     * hand exactly when a plugin gains or loses a reload command, the same trade {@code Topology} makes against
     * {@code compose.yml} for the same reason - the alternative is inferring live behaviour from a file nobody
     * parses at runtime.
     */
    private static final Map<String, String> RELOAD_COMMAND = Map.of(
            "smp/smp/milestones.yml", "smp reload",
            "smp/smp/sounds.yml", "smp reload",
            "smp/smp/colours.yml", "smp reload",
            "smp/smp/prestige.yml", "smp reload",
            "hunger-games/hunger-games/sounds.yml", "hg reload",
            // Message bundles, by MessagesApi's identity: reloads the plugin's own bundle and the shared one.
            "smp/smp", "smp reload",
            "hunger-games/hunger-games", "hg reload",
            "limbo/limbo", "limbo reload",
            "proxy/proxy", "network reload");

    /** This worker's own file, by the identity {@link #locate} matches against. */
    public static final String OWN_CONFIG = "steward-worker/steward.yml";

    private final Path root;
    private final ConsoleLine console;
    /**
     * Files this process reads itself, by identity, with what re-reads them.
     *
     * There is no console to send a reload line to for these - the reader is this process - so a save runs the
     * hook directly. Today that is only {@link #OWN_CONFIG}, whose schedules re-arm on it.
     */
    private final Map<String, Runnable> ownReloads;

    public ConfigApi(final Path root, final ConsoleLine console) {
        this(root, console, Map.of());
    }

    public ConfigApi(final Path root, final ConsoleLine console, final Map<String, Runnable> ownReloads) {
        this.root = root;
        this.console = console;
        this.ownReloads = Map.copyOf(ownReloads);
    }

    /** {@code GET /api/config} - every file under the mount, without reading any of them. */
    public void list(final Context ctx) {
        ctx.json(locations().stream().map(ConfigApi::describe).toList());
    }

    /**
     * {@code GET /api/config/<file>} - one file, as a form, or as raw text.
     *
     * {@code discover()} does not look at the extension, so this route is asked about files that were never YAML to
     * begin with - a plugin's {@code README.txt}, a {@code voicechat-server.properties}. Whatever will not parse as
     * a config file, one of those or an ordinary {@code .yml} with a mistake in it, answers with 200 and the file's
     * own bytes under {@code raw: true} rather than a 400: a file that cannot be split into keys can still be shown,
     * just not as a form.
     */
    public void one(final Context ctx) {
        final ConfigLocation location = locate(ctx);
        ctx.json(read(location));
    }

    /**
     * {@code PUT /api/config/<file>} - apply changes and answer with the file as it now reads.
     *
     * The body is {@code {"revision": "…", "changes": {"path": "value", "other.path": ["a"]}}}. A string is a scalar
     * and an array is a list, and the two are not interchangeable: {@link ConfigFiles} refuses a shape that does not
     * match the key, which is what stops a list of three services being replaced by the word "smp".
     *
     * {@code revision} is what the GET above handed out, and it is required: without it a later save would apply
     * its changes to whatever it found and write the result, and an earlier change would simply be gone with
     * nothing anywhere saying so. A stale one is a 409 and the page shows the file as it now stands.
     */
    public void save(final Context ctx) {
        final ConfigLocation location = locate(ctx);
        if (!location.writable()) {
            throw new ForbiddenResponse(location.name() + " is mounted read-only in this container,"
                    + " so this interface cannot save a change to it.");
        }
        final JsonObject body = bodyOf(ctx.body());
        final Map<String, ConfigChange> changes = changesOf(body);
        final String revision = revisionOf(body);
        try {
            final Map<String, Object> answer =
                    document(location, ConfigFiles.write(location.file(), changes, revision));
            // One request, one click: never a second button for "now actually use it".
            answer.put("reload", reload(location));
            ctx.json(answer);
        } catch (final StaleConfigException e) {
            log.info(
                    "{} was not saved: it was written since it was read ({} -> {})",
                    location.file(),
                    e.expected(),
                    e.actual());
            // The page re-reads the file when it sees the 409, so the refusal carries no stale file to draw from.
            throw new ConflictResponse(location.name() + " was changed by somebody else while this"
                    + " form was open, so nothing was saved. Read it again and make the change"
                    + " once more if it is still the one you want.");
        } catch (final IllegalArgumentException e) {
            throw new BadRequestResponse(e.getMessage());
        } catch (final IOException e) {
            // Javalin does not log a handled HttpResponseException on its own.
            log.error("{} could not be written", location.file(), e);
            throw new InternalServerErrorResponse(location.name() + " could not be written: " + e.getMessage());
        } catch (final IllegalStateException e) {
            // The rendered file would not read back as what was asked for: this program's bug, not the operator's.
            log.error("{} was not written: the rendered file would not read back", location.file(), e);
            throw new InternalServerErrorResponse(location.name() + " could not be written: " + e.getMessage());
        }
    }

    /**
     * {@code PUT /api/config-raw/<file>} - saves exactly the text typed into the raw editor.
     *
     * The body is {@code {"revision": "…", "content": "…"}} - no {@code changes} map, because there is no shape here
     * to check a change against: the raw editor exists for a file this class could not split into keys at all, or
     * one an operator wants to hand-edit byte for byte anyway. {@link ConfigFiles#writeRaw} writes exactly what it
     * is given.
     *
     * Nothing here is ever refused for what the text says. {@link RawSyntax#check} looks at the content against the
     * format its file name implies and, when it finds something, that becomes a warning in the response, never a
     * {@code 400} and never something that stops the write below from happening: a file that refuses to save is one
     * an operator has to work around. The only refusals left are the ones {@link #save} already has for reasons
     * that have nothing to do with syntax: the mount is read-only, or somebody else wrote the file since this editor
     * read it.
     */
    public void saveRaw(final Context ctx) {
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
            // A raw save of a file this process reads itself changes the schedule just as much.
            final Runnable own = ownReloads.get(identityOf(location));
            if (own != null) {
                reReadOwn(own, location);
            }
            final Map<String, Object> answer = new LinkedHashMap<>(describe(location));
            answer.put("raw", true);
            answer.put("revision", newRevision);
            answer.put("content", content);
            answer.put("warnings", warnings);
            ctx.json(answer);
        } catch (final StaleConfigException e) {
            // Exactly #save's own reasoning: nobody made a mistake, somebody else was faster.
            log.info(
                    "{} was not saved: it was written since it was read ({} -> {})",
                    location.file(),
                    e.expected(),
                    e.actual());
            throw new ConflictResponse(location.name() + " was changed by somebody else while this"
                    + " editor was open, so nothing was saved. Read it again and make the change"
                    + " once more if it is still the one you want.");
        } catch (final IOException e) {
            log.error("{} could not be written", location.file(), e);
            throw new InternalServerErrorResponse(location.name() + " could not be written: " + e.getMessage());
        }
    }

    // Finding the file

    private List<ConfigLocation> locations() {
        try {
            return ConfigFiles.discover(root);
        } catch (final UncheckedIOException e) {
            throw new InternalServerErrorResponse(
                    "The config mount at " + root + " could not be listed: " + e.getMessage());
        }
    }

    /**
     * The file the request is about, or a 404.
     *
     * {@code <file>} is the path under the mount as the listing reported it - so {@code steward-worker/steward.yml},
     * and {@code steward.yml} for a file lying directly in the root with no service directory above it. Javalin has
     * already decoded it once; it is compared, not resolved, so once more would make no difference either.
     */
    private ConfigLocation locate(final Context ctx) {
        final String asked = ctx.pathParam("file");
        return locations().stream()
                .filter(location -> identityOf(location).equals(asked))
                .findFirst()
                .orElseThrow(
                        () -> new NotFoundResponse("There is no config file called " + asked + " under " + root + "."));
    }

    /** How a file is named in a URL: what {@code discover} found, service directory included. */
    private static String identityOf(final ConfigLocation location) {
        return location.service().isEmpty() ? location.name() : location.service() + "/" + location.name();
    }

    // What goes over the wire

    static Map<String, Object> describe(final ConfigLocation location) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("service", location.service());
        row.put("name", location.name());
        row.put("path", identityOf(location));
        // Both, and in that order: a readable-but-not-writable file gets a form to look at and no save button.
        row.put("readable", location.readable());
        row.put("writable", location.writable());
        // Who wrote the file: only Nordtal's plugins have a name worth replacing the data folder with.
        final int slash = location.name().indexOf('/');
        final String folder = slash < 0 ? null : location.name().substring(0, slash);
        final String nordtal = folder == null ? null : Topology.NORDTAL_DATA_FOLDERS.get(folder);
        row.put("origin", folder == null || nordtal != null ? "nordtal" : "third-party");
        row.put("plugin", nordtal != null ? nordtal : folder);
        return row;
    }

    private Map<String, Object> read(final ConfigLocation location) {
        // Asked before the open: a permission problem is not a bad request, and IOException cannot say which.
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
            // Caught rather than asked: a directory above this file can refuse the open without `isReadable` saying so.
            log.warn("{} cannot be read by this process", location.file(), denied);
            throw new InternalServerErrorResponse(
                    location.name() + " is on this host but this" + " service may not open it.");
        } catch (final IOException e) {
            // Not a config file this class can split into keys: there is still something to show, the bytes on disk.
            final String reason = e.getMessage() == null ? e.toString() : e.getMessage();
            log.info("{} does not read as a config file; showing it as raw text: {}", location.file(), reason);
            return rawDocument(location, reason);
        }
    }

    // Package-private: ConfigApiReloadTest constructs a ConfigDocument directly to check what restartRequired says.
    static Map<String, Object> document(final ConfigLocation location, final ConfigDocument read) {
        final Map<String, Object> answer = new LinkedHashMap<>(describe(location));
        answer.put("revision", read.revision());
        answer.put("header", read.header());
        // Shown at the file, not only after a save: a setting nothing reloads says so the moment the form is open.
        answer.put("restartRequired", !RELOAD_COMMAND.containsKey(identityOf(location)));
        final List<Map<String, Object>> entries = new ArrayList<>(read.entries().size());
        for (final ConfigEntry entry : read.entries()) {
            entries.add(describe(entry));
        }
        answer.put("entries", entries);
        return answer;
    }

    /**
     * Asks the affected service to pick a just-written change up.
     *
     * Says in one word plus one sentence what happened.
     *
     * {@code RESTART_REQUIRED} when nothing in {@link #RELOAD_COMMAND} names this file: no command is sent, because
     * there is nothing this process could send that this file's own reload command would read. {@code APPLIED} when
     * the line was handed to a running container's console - {@link ConsoleLine#send} throwing nothing back only
     * means the exec succeeded, not that the plugin liked what it read; a malformed file the plugin refuses is
     * reported on that service's own console. {@code NO_ANSWER} when the container that would have run it is not
     * there to ask - a service that is down, mid-restart, or never started. Neither branch restarts anything: that
     * stays a deliberate click.
     */
    // Package-private for the same reason: ConfigApiReloadTest drives the three outcomes with a fake ConsoleLine.
    Map<String, Object> reload(final ConfigLocation location) {
        final Runnable own = ownReloads.get(identityOf(location));
        if (own != null) {
            return reReadOwn(own, location);
        }
        return reload(
                console,
                identityOf(location),
                location.service(),
                location.name(),
                location.file().toString());
    }

    /**
     * A file this process reads itself, read again.
     *
     * The save has already happened by the time this runs, so a failure here is reported rather than thrown: the
     * change is on disk and a restart will pick it up.
     */
    private static Map<String, Object> reReadOwn(final Runnable own, final ConfigLocation location) {
        final Map<String, Object> answer = new LinkedHashMap<>();
        try {
            own.run();
            answer.put("status", "APPLIED");
            answer.put(
                    "message",
                    "Saved, and " + location.service() + " read " + location.name()
                            + " again. The backup and update schedules apply at once; most other settings"
                            + " in it are still read only at a restart.");
        } catch (final RuntimeException e) {
            log.warn("{} was saved but could not be read again: {}", location.file(), e.getMessage());
            answer.put("status", "RESTART_REQUIRED");
            answer.put(
                    "message",
                    "Saved, but " + location.service() + " could not read it again: " + e.getMessage()
                            + ". The change takes effect at its next restart.");
        }
        return answer;
    }

    /**
     * The same three outcomes for anything {@link #RELOAD_COMMAND} names by {@code identity}.
     *
     * A config file here, a message bundle in {@link MessagesApi}.
     */
    static Map<String, Object> reload(
            final ConsoleLine console,
            final String identity,
            final String service,
            final String name,
            final String file) {
        final Map<String, Object> answer = new LinkedHashMap<>();
        final String command = RELOAD_COMMAND.get(identity);
        if (command == null) {
            answer.put("status", "RESTART_REQUIRED");
            answer.put(
                    "message",
                    "Saved. Nothing reloads " + name + " live; "
                            + (service.isEmpty() ? "it" : service)
                            + " only reads it again at its next restart, which stays a click of its own.");
            return answer;
        }
        try {
            console.send(service, command);
            answer.put("status", "APPLIED");
            answer.put(
                    "message",
                    "Saved, and \"" + command + "\" was sent to "
                            + service + "'s console to pick it up. Its reply, if the change was"
                            + " refused, appears in that service's own log.");
        } catch (final DockerException e) {
            log.warn("{} was saved but {} could not be reached to reload it: {}", file, service, e.getMessage());
            answer.put("status", "NO_ANSWER");
            answer.put(
                    "message",
                    "Saved, but " + service + " did not answer: "
                            + e.getMessage() + ". The change is on disk and takes effect once that service"
                            + " is running again.");
        } catch (final IllegalArgumentException e) {
            // A service losing its console without RELOAD_COMMAND being updated should read as "needs a restart".
            log.warn("{} names a reload command for {}, which refused it: {}", file, service, e.getMessage());
            answer.put("status", "RESTART_REQUIRED");
            answer.put("message", "Saved. " + e.getMessage());
        }
        return answer;
    }

    /**
     * A file that could not be read as YAML, shown and editable as itself instead of as a 400.
     *
     * {@code raw: true} marks "nothing here was parsed into keys", and {@code revision} is still present: {@link
     * #saveRaw} needs the same stale-write guard {@link #save} has, and {@link ConfigFiles#revisionOf} is computed
     * the same way for any text regardless of whether this class could split it into keys.
     */
    private static Map<String, Object> rawDocument(final ConfigLocation location, final String reason) {
        final Map<String, Object> answer = new LinkedHashMap<>(describe(location));
        answer.put("raw", true);
        answer.put("reason", reason);
        try {
            final String content =
                    java.nio.file.Files.readString(location.file(), java.nio.charset.StandardCharsets.UTF_8);
            answer.put("content", content);
            answer.put("revision", ConfigFiles.revisionOf(content));
        } catch (final IOException e) {
            log.warn("{} could not be read as raw text either", location.file(), e);
            throw new InternalServerErrorResponse(location.name() + " could not be read: " + e.getMessage());
        }
        return answer;
    }

    private static Map<String, Object> describe(final ConfigEntry entry) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("path", entry.path());
        row.put("key", entry.key());
        row.put("label", entry.label());
        row.put("comments", entry.comments());
        // The schema's own text: both are empty/false for a key no schema covers, drawn like a key with no comment.
        row.put("explanation", entry.explanation());
        row.put("noExplanationNeeded", entry.noExplanationNeeded());
        // `filled` is what a secret is allowed to say about itself, sent for every key so the page has one rule.
        row.put(
                "filled",
                !entry.value().isEmpty()
                        || !entry.items().isEmpty()
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
        describeOptionalFields(entry, row);
        if (entry.kind() == ConfigEntry.Kind.SECTIONS) {
            describeSections(entry, row);
        }
        return row;
    }

    // Left out, not sent as false or null, whenever the underlying value does not apply to this entry.
    private static void describeOptionalFields(final ConfigEntry entry, final Map<String, Object> row) {
        if (entry.environmentOverridden() != null) {
            row.put("environmentOverridden", entry.environmentOverridden());
        }
        if (entry.choices() != null) {
            final Map<String, Object> choices = new LinkedHashMap<>();
            choices.put("values", entry.choices().values());
            choices.put("strict", entry.choices().strict());
            row.put("choices", choices);
        }
        // Which of this list's sections may not be removed. Only a SECTIONS entry can have one, and most do not.
        if (entry.protectedEntry() != null) {
            final Map<String, Object> protectedEntry = new LinkedHashMap<>();
            protectedEntry.put("field", entry.protectedEntry().field());
            protectedEntry.put("value", entry.protectedEntry().value());
            row.put("protectedEntry", protectedEntry);
        }
    }

    // template and sections exist only for a SECTIONS entry; the key is left out for every other kind.
    private static void describeSections(final ConfigEntry entry, final Map<String, Object> row) {
        if (!entry.template().isEmpty()) {
            row.put(
                    "template",
                    entry.template().stream().map(ConfigApi::describe).toList());
        }
        row.put(
                "sections",
                entry.sections().stream()
                        .map(section ->
                                section.stream().map(ConfigApi::describe).toList())
                        .toList());
    }

    // What comes in

    private static JsonObject bodyOf(final String body) {
        try {
            return JsonParser.parseString(body == null ? "" : body).getAsJsonObject();
        } catch (final JsonSyntaxException | IllegalStateException | IllegalArgumentException e) {
            throw new BadRequestResponse("The body has to be a JSON object with `revision` and" + " `changes` fields.");
        }
    }

    /**
     * The revision the browser was last shown.
     *
     * Required, and deliberately not optional-with-a-default: a save that may omit it is a save every client can
     * accidentally make unconditional, and the one that forgets is the one that quietly overwrites somebody. A
     * caller that genuinely wants to write over whatever is there reads the file first, which takes one request.
     */
    private static String revisionOf(final JsonObject body) {
        final JsonElement revision = body.get("revision");
        if (revision == null
                || !revision.isJsonPrimitive()
                || revision.getAsString().isBlank()) {
            throw new BadRequestResponse("`revision` has to be the value this file was last read"
                    + " with, so that a change somebody else made in the meantime is not"
                    + " overwritten.");
        }
        return revision.getAsString();
    }

    /**
     * The raw text a {@link #saveRaw} body carries under {@code content} - required.
     *
     * An empty string is a perfectly good value: an operator emptying a file on purpose is not a malformed request.
     */
    private static String contentOf(final JsonObject body) {
        final JsonElement content = body.get("content");
        if (content == null || !content.isJsonPrimitive()) {
            throw new BadRequestResponse("`content` has to be the text to write, as a string.");
        }
        return content.getAsString();
    }

    static Map<String, ConfigChange> changesOf(final JsonObject asked) {
        final JsonElement changes = asked.get("changes");
        if (changes == null || !changes.isJsonObject()) {
            throw new BadRequestResponse("`changes` has to be an object of setting to new value.");
        }

        final Map<String, ConfigChange> answer = new LinkedHashMap<>();
        for (final Map.Entry<String, JsonElement> change :
                changes.getAsJsonObject().entrySet()) {
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
     * The entries of a {@link ConfigEntry.Kind#SECTIONS} change - an array whose first element is a JSON object.
     *
     * Every element has to follow the same shape; a mix is reported rather than silently coerced, since
     * {@link ConfigFiles} has no way to tell whether a stray scalar there was meant as a whole new entry or a
     * mistake.
     */
    private static List<Map<String, Object>> sectionsOf(final String path, final JsonArray array) {
        final List<Map<String, Object>> sections = new ArrayList<>(array.size());
        for (final JsonElement item : array) {
            if (!item.isJsonObject()) {
                throw new BadRequestResponse(path + ": every entry of a list of sections has to be"
                        + " an object of field to new value, not " + item);
            }
            final Map<String, Object> fields = new LinkedHashMap<>();
            for (final Map.Entry<String, JsonElement> field :
                    item.getAsJsonObject().entrySet()) {
                fields.put(field.getKey(), fieldOf(path + "." + field.getKey(), field.getValue()));
            }
            sections.add(fields);
        }
        return sections;
    }

    /**
     * One field of a section: text, a list of values, or a list of sections again.
     *
     * One level further down, the objectives of a milestone, for example. An empty array is left to
     * {@link ConfigFiles}, which knows from the file which of the two lists it is.
     */
    private static Object fieldOf(final String path, final JsonElement value) {
        if (!value.isJsonArray()) {
            return textOf(path, value);
        }
        final JsonArray array = value.getAsJsonArray();
        if (!array.isEmpty() && array.get(0).isJsonObject()) {
            return sectionsOf(path, array);
        }
        final List<String> items = new ArrayList<>(array.size());
        for (final JsonElement item : array) {
            items.add(textOf(path, item));
        }
        return items;
    }

    /**
     * A value as text.
     *
     * A number and a boolean are accepted and turned into their own characters, because a form that sends
     * {@code 8080} rather than {@code "8080"} is a form doing something reasonable. {@link ConfigFiles} then
     * decides whether the key can hold it.
     */
    private static String textOf(final String path, final JsonElement value) {
        if (value.isJsonPrimitive()) {
            return value.getAsString();
        }
        if (value.isJsonNull()) {
            return "";
        }
        throw new BadRequestResponse(path + ": a setting is a value or a list of values, not " + value);
    }

    /** For the page that lists the mount: whether there is anything there at all. */
    public Optional<String> whatIsMissing() {
        return locations().isEmpty() ? Optional.of("Nothing is mounted at " + root) : Optional.empty();
    }
}
