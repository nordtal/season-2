package eu.nordtal.s2.steward.worker.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import eu.nordtal.s2.common.access.AccessRequest;
import eu.nordtal.s2.common.access.AccessRequestKind;
import eu.nordtal.s2.common.access.AccessRequestSource;
import eu.nordtal.s2.common.access.AccessRequestStatus;
import eu.nordtal.s2.common.access.AccessRequests;
import eu.nordtal.s2.steward.worker.configfile.MessageArg;
import eu.nordtal.s2.steward.worker.configfile.MessageBundle;
import eu.nordtal.s2.steward.worker.configfile.MessageBundleLocation;
import eu.nordtal.s2.steward.worker.configfile.MessageBundles;
import eu.nordtal.s2.steward.worker.configfile.MessageEntry;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.InternalServerErrorResponse;
import io.javalin.http.NotFoundResponse;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The two routes over {@code eu.nordtal.s2.steward.worker.configfile.MessageBundles} (steward/48).
 *
 * <p>Deliberately its own class rather than three more methods on {@link ConfigApi}: a bundle is not
 * a config file - it has no YAML shape, no schema, and its own key-by-key override rule - and the
 * interface is asked to draw it as its own card, beside the configuration cards rather than inside
 * them. Keeping the two APIs apart is what makes that true on this side of the wire as well: nothing
 * a bundle needs changes what {@code GET /api/config} answers.</p>
 */
public final class MessagesApi {

    private static final Logger log = LoggerFactory.getLogger(MessagesApi.class);

    /**
     * The one bundle whose reload does not go through a console: the bot is not a Minecraft server,
     * so it is asked through the inbox it already listens on. Every other bundle is in
     * {@code ConfigApi}'s table.
     */
    private static final String RELOADABLE_SERVICE = "discord-bot";

    /**
     * How long the browser waits for the bot, and how long the row waits for the bot.
     *
     * <p>The two are the same number on purpose. A row that outlived the answer would be carried
     * out by a bot that came back a minute later, after this interface had already said "takes
     * effect after a restart" - and then both sentences would be true at different moments, which
     * is the one outcome worse than either. Five seconds is far longer than a listening bot needs:
     * the insert carries its own {@code pg_notify}, so the wake-up is not waiting on a poll.</p>
     */
    private static final Duration ANSWER_WITHIN = Duration.ofSeconds(5);

    /** How often the answer is looked for while waiting - see {@link #ANSWER_WITHIN}. */
    private static final Duration LOOK_EVERY = Duration.ofMillis(100);

    /**
     * Who the row is filed under.
     *
     * <p>A fixed name, unlike the other five kinds, and that is not laziness: a reload sends no
     * direct message and writes no admin line, so the only thing the requester would be used for is
     * the row itself. This process does not know which browser asked - steward-ui holds the session
     * and the worker sees a service token - and inventing a person here would be the kind of
     * plausible-looking lie an audit trail is exactly the wrong place for.</p>
     */
    private static final String ASKED_BY = "steward-ui";

    private final Path configsRoot;
    private final Path volumesRoot;
    private final AccessRequests inbox;
    private final ConfigApi.ConsoleLine console;

    public MessagesApi(final Path configsRoot, final @Nullable Path volumesRoot) {
        this(configsRoot, volumesRoot, null, (service, command) -> {
            throw new IllegalArgumentException(service + " has no console here");
        });
    }

    /**
     * @param inbox the access inbox the bot listens on, or {@code null} in a deployment with no
     *              database - {@link #reload} then answers that a restart is needed, which is what
     *              is actually true there
     * @param console the Minecraft services' consoles, which a saved bundle's reload goes through
     */
    public MessagesApi(
            final Path configsRoot,
            final @Nullable Path volumesRoot,
            final @Nullable AccessRequests inbox,
            final ConfigApi.ConsoleLine console) {
        this.configsRoot = configsRoot;
        this.volumesRoot = volumesRoot;
        this.inbox = inbox;
        this.console = console;
    }

    /** {@code GET /api/messages} - every bundle found, without opening a single jar. */
    public void list(final Context ctx) {
        ctx.json(locations().stream().map(MessagesApi::describe).toList());
    }

    /** {@code GET /api/messages/<bundle>} - one bundle, packaged text and override side by side. */
    public void one(final Context ctx) {
        final MessageBundleLocation location = locate(ctx);
        try {
            ctx.json(document(location, MessageBundles.read(location)));
        } catch (final IOException e) {
            log.error("{} could not be read", location.jar(), e);
            throw new InternalServerErrorResponse(identityOf(location) + " could not be read: " + e.getMessage());
        }
    }

    /**
     * {@code PUT /api/messages/<bundle>} - apply changes to both languages' override files at once.
     *
     * <p>The body is {@code {"changes": {"key": {"en": "new text", "de": null}}}}.
     * A {@code null} value resets that key - it is removed from the override rather than filled with
     * the packaged text, so the line goes back to following the jar (steward/48).</p>
     *
     * <p><b>A dropped placeholder is a warning, never a refusal</b> - the same rule steward/60 gives
     * a syntax error in the raw editor. The response always carries {@code warnings}, empty when
     * there was nothing to say.</p>
     *
     * <p><b>A placeholder the schema does not declare is a refusal</b>, and nothing of the request is
     * written: the plugin fills the declared arguments and nothing else, so the line would draw its
     * {@code {name}} literally. The 400 names the key.</p>
     */
    public void save(final Context ctx) {
        final MessageBundleLocation location = locate(ctx);
        if (!location.writable()) {
            throw new ForbiddenResponse(identityOf(location) + " is mounted read-only in this"
                    + " container, so this interface cannot save a change to it.");
        }
        final Map<String, Map<String, String>> byLanguage = changesOf(bodyOf(ctx.body()));

        final MessageBundle before;
        try {
            before = MessageBundles.read(location);
        } catch (final IOException e) {
            log.error("{} could not be read", location.jar(), e);
            throw new InternalServerErrorResponse(identityOf(location) + " could not be read: " + e.getMessage());
        }
        final List<String> warnings = new ArrayList<>();
        for (final Map<String, String> changes : byLanguage.values()) {
            refuseUnknownPlaceholders(before, changes);
        }
        byLanguage.forEach((language, changes) -> warnings.addAll(warningsOf(before, language, changes)));

        try {
            for (final Map.Entry<String, Map<String, String>> language : byLanguage.entrySet()) {
                MessageBundles.write(location, language.getKey(), language.getValue());
            }
        } catch (final IllegalArgumentException e) {
            throw new BadRequestResponse(e.getMessage());
        } catch (final IOException e) {
            log.error("{} could not be written", location.overrideDirectory(), e);
            throw new InternalServerErrorResponse(identityOf(location) + " could not be written: " + e.getMessage());
        }

        try {
            final Map<String, Object> answer = document(location, MessageBundles.read(location));
            answer.put("warnings", warnings);
            answer.put("reload", reload(location));
            ctx.json(answer);
        } catch (final IOException e) {
            log.error("{} could not be read back after saving", location.jar(), e);
            throw new InternalServerErrorResponse(
                    identityOf(location) + " was saved but could not" + " be read back: " + e.getMessage());
        }
    }

    /**
     * Asks the service that owns a just-saved bundle to re-read it, answered under {@code reload}
     * in the save's own response.
     *
     * <p>In the vocabulary {@code ConfigApi#reload} already gave a config file - {@code APPLIED},
     * {@code NO_ANSWER} or {@code RESTART_REQUIRED} plus a {@code message} - and a Minecraft bundle
     * goes through that same table and that same console. The bot has no console, so its bundle
     * rides the inbox it already listens on.</p>
     *
     * <p>One field is added: {@code unknown}, the keys the override file declares that the bundle
     * has never heard of. Only the bot reports it; a typo there does nothing at all and says
     * nothing at all otherwise.</p>
     */
    private Map<String, Object> reload(final MessageBundleLocation location) {
        if (!RELOADABLE_SERVICE.equals(location.service())) {
            final Map<String, Object> answer = ConfigApi.reload(
                    console, identityOf(location), location.service(), identityOf(location), identityOf(location));
            answer.put("unknown", List.of());
            return answer;
        }
        if (inbox == null) {
            return outcome(
                    "RESTART_REQUIRED",
                    "Nothing was sent: this deployment has no database" + " to ask the bot through.",
                    List.of());
        }
        final AccessRequest asked = inbox.submit(
                new AccessRequests.NewAccessRequest(
                        AccessRequestKind.RELOAD_MESSAGES,
                        identityOf(location),
                        null,
                        AccessRequestSource.STEWARD,
                        ASKED_BY),
                ANSWER_WITHIN);
        final AccessRequest settled = waitFor(asked.id());
        if (settled == null || settled.status() == AccessRequestStatus.EXPIRED) {
            return outcome(
                    "NO_ANSWER",
                    "The bot did not answer, so the text that was saved takes" + " effect the next time it starts.",
                    List.of());
        }
        if (settled.status() != AccessRequestStatus.DONE) {
            log.warn("{} was saved but the bot could not re-read it: {}", identityOf(location), settled.result());
            return outcome(
                    "NO_ANSWER",
                    "The bot could not re-read its messages, so the running"
                            + " ones are unchanged and the saved text takes effect the next time it"
                            + " starts.",
                    List.of());
        }
        final List<String> unknown = unknownIn(settled.result());
        return outcome(
                "APPLIED",
                unknown.isEmpty()
                        ? "The bot re-read its messages."
                        : "The bot re-read its messages. It has no key called " + String.join(", ", unknown) + ".",
                unknown);
    }

    private static Map<String, Object> outcome(final String status, final String message, final List<String> unknown) {
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("status", status);
        answer.put("message", message);
        answer.put("unknown", unknown);
        return answer;
    }

    /**
     * @return the row once it has stopped being pending, or {@code null} if it has not within
     *         {@link #ANSWER_WITHIN} - which is a bot that is not running, and is not an error
     */
    private AccessRequest waitFor(final long id) {
        final long giveUpAt = System.nanoTime() + ANSWER_WITHIN.plusSeconds(1).toNanos();
        while (System.nanoTime() < giveUpAt) {
            final AccessRequest row = inbox.outcome(id).orElse(null);
            if (row != null && row.status().settled()) {
                return row;
            }
            try {
                Thread.sleep(LOOK_EVERY.toMillis());
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * The {@code unknown} field of the bot's own answer, split back into a list.
     *
     * <p>The row carries the bot's JSON verbatim - see {@code AccessRequests#finish} on why no
     * surface composes a second rendering of it - and the bot writes one comma-joined string
     * because {@code AccessInbox#json} takes pairs of strings. Splitting it here is the whole
     * translation, and an empty string is no keys rather than one empty key.</p>
     */
    private static List<String> unknownIn(final String result) {
        if (result == null || result.isBlank()) {
            return List.of();
        }
        try {
            final JsonElement parsed = JsonParser.parseString(result);
            if (!parsed.isJsonObject()) {
                return List.of();
            }
            final JsonElement unknown = parsed.getAsJsonObject().get("unknown");
            if (unknown == null
                    || !unknown.isJsonPrimitive()
                    || unknown.getAsString().isBlank()) {
                return List.of();
            }
            return List.of(unknown.getAsString().split(","));
        } catch (final JsonSyntaxException | IllegalStateException malformed) {
            log.warn("the bot answered a reload with something that is not the expected JSON: {}", result);
            return List.of();
        }
    }

    private static void refuseUnknownPlaceholders(final MessageBundle before, final Map<String, String> changes) {
        final List<String> problems = new ArrayList<>();
        for (final Map.Entry<String, String> change : changes.entrySet()) {
            final MessageEntry entry = before.entries().stream()
                    .filter(candidate -> candidate.key().equals(change.getKey()))
                    .findFirst()
                    .orElse(null);
            if (entry == null) {
                continue;
            }
            final List<String> unknown = MessageBundles.unknownPlaceholders(entry, change.getValue());
            if (!unknown.isEmpty()) {
                problems.add(change.getKey() + " has no placeholder " + String.join(", ", unknown)
                        + (entry.args().isEmpty()
                                ? "; it takes none."
                                : "; it takes "
                                        + String.join(
                                                ", ",
                                                entry.args().stream()
                                                        .map(MessageArg::token)
                                                        .toList()) + "."));
            }
        }
        if (!problems.isEmpty()) {
            throw new BadRequestResponse(String.join(" ", problems) + " Nothing was saved.");
        }
    }

    /**
     * A dropped placeholder for every changed key that had one, checked against the packaged text -
     * the "original" the ticket means, not whatever the override said a moment ago.
     */
    private static List<String> warningsOf(
            final MessageBundle before, final String language, final Map<String, String> changes) {
        final List<String> warnings = new ArrayList<>();
        for (final Map.Entry<String, String> change : changes.entrySet()) {
            final String edited = change.getValue();
            if (edited == null) {
                // A reset. There is no new text to check placeholders against.
                continue;
            }
            final MessageEntry entry = before.entries().stream()
                    .filter(candidate -> candidate.key().equals(change.getKey()))
                    .findFirst()
                    .orElse(null);
            if (entry == null) {
                continue;
            }
            final String original = "de".equals(language) && entry.german() != null ? entry.german() : entry.english();
            final List<String> missing = MessageBundles.missingPlaceholders(original, edited);
            if (!missing.isEmpty()) {
                warnings.add(change.getKey() + " no longer contains " + String.join(", ", missing)
                        + " - the original had it, and a message this is substituted into may now"
                        + " draw literally.");
            }
        }
        return warnings;
    }

    // ---------------------------------------------------------------------------------------
    // Finding the bundle
    // ---------------------------------------------------------------------------------------

    private List<MessageBundleLocation> locations() {
        return MessageBundles.discover(configsRoot, volumesRoot);
    }

    private MessageBundleLocation locate(final Context ctx) {
        final String asked = ctx.pathParam("bundle");
        return locations().stream()
                .filter(location -> identityOf(location).equals(asked))
                .findFirst()
                .orElseThrow(() -> new NotFoundResponse("There is no message bundle called " + asked + "."));
    }

    /** How a bundle is named in a URL: {@code <service>/<module>}, or just {@code <service>}. */
    private static String identityOf(final MessageBundleLocation location) {
        return location.module().isEmpty() ? location.service() : location.service() + "/" + location.module();
    }

    // ---------------------------------------------------------------------------------------
    // What goes over the wire
    // ---------------------------------------------------------------------------------------

    private static Map<String, Object> describe(final MessageBundleLocation location) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("service", location.service());
        row.put("module", location.module());
        row.put("path", identityOf(location));
        row.put("writable", location.writable());
        return row;
    }

    private static Map<String, Object> document(final MessageBundleLocation location, final MessageBundle bundle) {
        final Map<String, Object> answer = new LinkedHashMap<>(describe(location));
        final List<Map<String, Object>> entries =
                new ArrayList<>(bundle.entries().size());
        for (final MessageEntry entry : bundle.entries()) {
            entries.add(describe(entry));
        }
        answer.put("entries", entries);
        return answer;
    }

    private static Map<String, Object> describe(final MessageEntry entry) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", entry.key());
        // Gson drops a null-valued map entry by default rather than writing `null` (unlike a field
        // on a POJO), and this class does not own the app-wide JsonMapper to turn serializeNulls()
        // on without changing what every other route answers. So absence IS the "no text in this
        // language" signal here, the same way ConfigApi already leaves `value`/`items` off a secret
        // entry rather than sending them as null.
        putIfPresent(row, "english", entry.english());
        putIfPresent(row, "german", entry.german());
        putIfPresent(row, "overrideEnglish", entry.overrideEnglish());
        putIfPresent(row, "overrideGerman", entry.overrideGerman());
        row.put("inBundle", entry.inBundle());
        putIfPresent(row, "name", entry.name());
        putIfPresent(row, "description", entry.description());
        final List<Map<String, Object>> args = new ArrayList<>(entry.args().size());
        for (final MessageArg arg : entry.args()) {
            final Map<String, Object> described = new LinkedHashMap<>();
            described.put("name", arg.name());
            described.put("component", arg.component());
            if (arg.type() != null) {
                described.put("type", arg.type());
                described.put("global", arg.global());
            }
            args.add(described);
        }
        row.put("args", args);
        row.put("section", entry.section());
        putIfPresent(row, "format", entry.format());
        putIfPresent(row, "shown", entry.shown());
        return row;
    }

    private static void putIfPresent(final Map<String, Object> row, final String key, final String value) {
        if (value != null) {
            row.put(key, value);
        }
    }

    // ---------------------------------------------------------------------------------------
    // What comes in
    // ---------------------------------------------------------------------------------------

    private static JsonObject bodyOf(final String body) {
        try {
            return JsonParser.parseString(body == null ? "" : body).getAsJsonObject();
        } catch (final JsonSyntaxException | IllegalStateException | IllegalArgumentException e) {
            throw new BadRequestResponse("The body has to be a JSON object with a `changes` field.");
        }
    }

    /**
     * {@code {"changes": {"key": {"en": "text", "de": null}}}} split by language, English first -
     * {@code null} resets that language of that key.
     */
    private static Map<String, Map<String, String>> changesOf(final JsonObject body) {
        final JsonElement changes = body.get("changes");
        if (changes == null || !changes.isJsonObject()) {
            throw new BadRequestResponse("`changes` has to be an object of key to {\"en\": text,"
                    + " \"de\": text}, where null resets that language.");
        }
        final Map<String, Map<String, String>> byLanguage = new LinkedHashMap<>();
        byLanguage.put("en", new LinkedHashMap<>());
        byLanguage.put("de", new LinkedHashMap<>());
        for (final Map.Entry<String, JsonElement> change :
                changes.getAsJsonObject().entrySet()) {
            if (!change.getValue().isJsonObject()) {
                throw new BadRequestResponse(
                        change.getKey() + " has to be an object of language to" + " text, like {\"en\": \"...\"}.");
            }
            for (final Map.Entry<String, JsonElement> text :
                    change.getValue().getAsJsonObject().entrySet()) {
                final Map<String, String> into = byLanguage.get(text.getKey());
                if (into == null) {
                    throw new BadRequestResponse("A language has to be \"en\" or \"de\", not " + text.getKey() + ".");
                }
                final JsonElement value = text.getValue();
                into.put(change.getKey(), value == null || value.isJsonNull() ? null : value.getAsString());
            }
        }
        byLanguage.values().removeIf(Map::isEmpty);
        if (byLanguage.isEmpty()) {
            throw new BadRequestResponse("`changes` is empty - there is nothing to save.");
        }
        return byLanguage;
    }
}
