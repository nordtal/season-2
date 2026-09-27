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
 * The three routes over the config mount: list, read and save.
 *
 * A file is found by matching the discovered list, never by joining a path, and a secret's value never leaves.
 */
public final class ConfigApi {

    private static final Logger log = LoggerFactory.getLogger(ConfigApi.class);

    /** One line into a running server's console, narrowed so a test can pass a lambda. */
    @FunctionalInterface
    public interface ConsoleLine {
        void send(String service, String command);
    }

    /**
     * Which running service to poke, and with what line, once a save changes a file.
     *
     * Keyed per file, since only some files of a service are re-read by its reload command; it grows by hand.
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
    /** Files this process reads itself, by identity, with the hook that re-reads them. */
    private final Map<String, Runnable> ownReloads;

    public ConfigApi(final Path root, final ConsoleLine console) {
        this(root, console, Map.of());
    }

    public ConfigApi(final Path root, final ConsoleLine console, final Map<String, Runnable> ownReloads) {
        this.root = root;
        this.console = console;
        this.ownReloads = Map.copyOf(ownReloads);
    }

    /** {@code GET /api/config}: every file under the mount, without reading any of them. */
    public void list(final Context ctx) {
        ctx.json(locations().stream().map(ConfigApi::describe).toList());
    }

    /**
     * {@code GET /api/config/<file>}: one file, as a form, or as raw text.
     *
     * A file that will not parse is answered with 200 and its own bytes under {@code raw: true}.
     */
    public void one(final Context ctx) {
        final ConfigLocation location = locate(ctx);
        ctx.json(read(location));
    }

    /**
     * {@code PUT /api/config/<file>}: applies changes and answers with the file as it now reads.
     *
     * The body carries the {@code revision} the GET handed out; a stale one is a 409.
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
            // One request, one click, never a second button to apply.
            answer.put("reload", reload(location));
            ctx.json(answer);
        } catch (final StaleConfigException e) {
            log.info(
                    "{} was not saved: it was written since it was read ({} -> {})",
                    location.file(),
                    e.expected(),
                    e.actual());
            // The page re-reads the file on a 409, so the refusal carries no file.
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
            // The rendered file would not read back as asked: this program's bug, not the operator's.
            log.error("{} was not written: the rendered file would not read back", location.file(), e);
            throw new InternalServerErrorResponse(location.name() + " could not be written: " + e.getMessage());
        }
    }

    /**
     * {@code PUT /api/config-raw/<file>}: saves exactly the text typed into the raw editor.
     *
     * A syntax problem only becomes a warning; the only refusals are a read-only mount and a stale revision.
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
            // A raw save of a file this process reads itself re-arms it just the same.
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
            // Nobody made a mistake, somebody else was faster.
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

    /** The file the request is about, matched against the listing, or a 404. */
    private ConfigLocation locate(final Context ctx) {
        final String asked = ctx.pathParam("file");
        return locations().stream()
                .filter(location -> identityOf(location).equals(asked))
                .findFirst()
                .orElseThrow(
                        () -> new NotFoundResponse("There is no config file called " + asked + " under " + root + "."));
    }

    /** How a file is named in a URL: its path under the mount. */
    private static String identityOf(final ConfigLocation location) {
        return location.service().isEmpty() ? location.name() : location.service() + "/" + location.name();
    }

    // What goes over the wire

    static Map<String, Object> describe(final ConfigLocation location) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("service", location.service());
        row.put("name", location.name());
        row.put("path", identityOf(location));
        // Both, in that order: a readable but not writable file gets a form and no save button.
        row.put("readable", location.readable());
        row.put("writable", location.writable());
        // Only Nordtal's plugins have a name worth replacing the data folder with.
        final int slash = location.name().indexOf('/');
        final String folder = slash < 0 ? null : location.name().substring(0, slash);
        final String nordtal = folder == null ? null : Topology.NORDTAL_DATA_FOLDERS.get(folder);
        row.put("origin", folder == null || nordtal != null ? "nordtal" : "third-party");
        row.put("plugin", nordtal != null ? nordtal : folder);
        return row;
    }

    private Map<String, Object> read(final ConfigLocation location) {
        // Asked before the open, since a permission problem is not a bad request.
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
            // Caught too: a directory above can refuse the open without `isReadable` saying so.
            log.warn("{} cannot be read by this process", location.file(), denied);
            throw new InternalServerErrorResponse(
                    location.name() + " is on this host but this" + " service may not open it.");
        } catch (final IOException e) {
            // Not splittable into keys, but the bytes on disk can still be shown.
            final String reason = e.getMessage() == null ? e.toString() : e.getMessage();
            log.info("{} does not read as a config file; showing it as raw text: {}", location.file(), reason);
            return rawDocument(location, reason);
        }
    }

    // Package-private for ConfigApiReloadTest.
    static Map<String, Object> document(final ConfigLocation location, final ConfigDocument read) {
        final Map<String, Object> answer = new LinkedHashMap<>(describe(location));
        answer.put("revision", read.revision());
        answer.put("header", read.header());
        // Shown at the file, so a setting nothing reloads says so as soon as the form opens.
        answer.put("restartRequired", !RELOAD_COMMAND.containsKey(identityOf(location)));
        final List<Map<String, Object>> entries = new ArrayList<>(read.entries().size());
        for (final ConfigEntry entry : read.entries()) {
            entries.add(describe(entry));
        }
        answer.put("entries", entries);
        return answer;
    }

    /**
     * Asks the affected service to pick a just-written change up; never restarts anything.
     *
     * Answers {@code RESTART_REQUIRED}, {@code APPLIED} when the console took the line, or {@code NO_ANSWER}.
     */
    // Package-private: ConfigApiReloadTest drives the three outcomes with a fake ConsoleLine.
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
     * The save has already happened, so a failure here is reported rather than thrown.
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

    /** The same three outcomes for anything {@link #RELOAD_COMMAND} names by {@code identity}. */
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
            // A service that lost its console while still listed should read as needing a restart.
            log.warn("{} names a reload command for {}, which refused it: {}", file, service, e.getMessage());
            answer.put("status", "RESTART_REQUIRED");
            answer.put("message", "Saved. " + e.getMessage());
        }
        return answer;
    }

    /**
     * A file that could not be read as YAML, shown and editable as itself instead of as a 400.
     *
     * It still carries a {@code revision}, so a raw save has the same stale-write guard.
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
        // Both are empty for a key no schema covers, drawn like a key with no comment.
        row.put("explanation", entry.explanation());
        row.put("noExplanationNeeded", entry.noExplanationNeeded());
        // `filled` is all a secret may say about itself, sent for every key so the page has one rule.
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

    // Left out, not sent as false or null, whenever the value does not apply to this entry.
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
        // Only a SECTIONS entry can have protected sections, and most do not.
        if (entry.protectedEntry() != null) {
            final Map<String, Object> protectedEntry = new LinkedHashMap<>();
            protectedEntry.put("field", entry.protectedEntry().field());
            protectedEntry.put("value", entry.protectedEntry().value());
            row.put("protectedEntry", protectedEntry);
        }
    }

    // template and sections exist only for a SECTIONS entry.
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

    /** The revision the browser was last shown, required so no client can make a save unconditional by accident. */
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
     * The raw text under {@code content}, required.
     *
     * An empty string is a valid value: an operator may empty a file on purpose.
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
     * The entries of a {@link ConfigEntry.Kind#SECTIONS} change: an array whose first element is an object.
     *
     * A mix of shapes is refused rather than coerced.
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
     * An empty array is left to {@link ConfigFiles}, which knows which list it is.
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
     * A value as text; a number or a boolean becomes its own characters.
     *
     * {@link ConfigFiles} then decides whether the key can hold it.
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

    /** Whether there is anything under the mount at all, for the listing page. */
    public Optional<String> whatIsMissing() {
        return locations().isEmpty() ? Optional.of("Nothing is mounted at " + root) : Optional.empty();
    }
}
