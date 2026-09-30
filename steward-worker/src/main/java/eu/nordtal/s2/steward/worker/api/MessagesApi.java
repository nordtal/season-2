package eu.nordtal.s2.steward.worker.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.common.time.Backoff;
import eu.nordtal.s2.common.time.Waiting;
import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.inbox.InboxStatus;
import eu.nordtal.s2.database.inbox.Request;
import eu.nordtal.s2.database.inbox.Schedule;
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
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The two routes over the message bundles, kept apart from {@link ConfigApi} since a bundle is not a config file. */
public final class MessagesApi {

    private static final Logger log = LoggerFactory.getLogger(MessagesApi.class);

    /** The one bundle whose reload goes through the bot's inbox, which answers with the keys it does not know. */
    private static final String RELOADABLE_SERVICE = "discord-bot";

    /**
     * How long the browser waits for the bot, and how long the row waits for the bot.
     *
     * The same number, so a late bot cannot apply a reload the page already called a restart.
     */
    private static final Duration ANSWER_WITHIN = Duration.ofSeconds(5);

    /** How often the answer is looked for while waiting. */
    private static final Duration LOOK_EVERY = Duration.ofMillis(100);

    private static final Pattern COMMA = Pattern.compile(",");

    private final Waiting waiting;

    private final Path configsRoot;
    private final @Nullable Path volumesRoot;
    private final @Nullable Inbox<BotRequest> inbox;
    private final ConfigApi.Reloader reloader;

    /**
     * Builds the API.
     *
     * @param inbox the bot's inbox, or {@code null} without a database, which makes {@link #reload}
     *     ask for a restart
     * @param reloader asks a Minecraft service to re-read a saved bundle
     */
    public MessagesApi(
            final Path configsRoot,
            final @Nullable Path volumesRoot,
            final @Nullable Inbox<BotRequest> inbox,
            final ConfigApi.Reloader reloader,
            final Waiting waiting) {
        this.waiting = waiting;
        this.configsRoot = configsRoot;
        this.volumesRoot = volumesRoot;
        this.inbox = inbox;
        this.reloader = reloader;
    }

    /** {@code GET /api/messages}: every bundle found, without opening a single jar. */
    public void list(final Context ctx) {
        ctx.json(locations().stream().map(MessagesApi::describe).toList());
    }

    /** {@code GET /api/messages/<bundle>}: one bundle, packaged text and override side by side. */
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
     * {@code PUT /api/messages/<bundle>}: applies changes to both languages' override files at once.
     *
     * A {@code null} resets a key; a dropped placeholder is a warning, an undeclared one a 400 that writes nothing.
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
     * Asks the service that owns a just-saved bundle to re-read it, answered like {@code ConfigApi#reload}.
     *
     * The bot's answer adds {@code unknown}: the keys its override file declares that the bundle does not know.
     */
    private Map<String, Object> reload(final MessageBundleLocation location) {
        if (!RELOADABLE_SERVICE.equals(location.service())) {
            final Map<String, Object> answer = ConfigApi.reload(
                    reloader, identityOf(location), location.service(), identityOf(location), identityOf(location));
            answer.put("unknown", List.of());
            return answer;
        }
        final Inbox<BotRequest> requests = inbox;
        if (requests == null) {
            return outcome(
                    "RESTART_REQUIRED",
                    "Nothing was sent: this deployment has no database" + " to ask the bot through.",
                    List.of());
        }
        final Request<BotRequest> asked = requests.submit(
                new BotRequest.ReloadMessages(identityOf(location)),
                // Steward itself: this process does not know which browser asked and must not invent one.
                Actor.STEWARD,
                Schedule.within(ANSWER_WITHIN));
        final Request<BotRequest> settled = waitFor(requests, asked.id());
        if (settled == null || settled.status() == InboxStatus.EXPIRED) {
            return outcome(
                    "NO_ANSWER",
                    "The bot did not answer, so the text that was saved takes" + " effect the next time it starts.",
                    List.of());
        }
        if (settled.status() != InboxStatus.DONE) {
            log.warn("{} was saved but the bot could not re-read it: {}", identityOf(location), settled.outcome());
            return outcome(
                    "NO_ANSWER",
                    "The bot could not re-read its messages, so the running"
                            + " ones are unchanged and the saved text takes effect the next time it"
                            + " starts.",
                    List.of());
        }
        final List<String> unknown = unknownIn(settled.outcome());
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
     * Polls the row until it settles.
     *
     * @return the row once it is no longer pending, or {@code null} after {@link #ANSWER_WITHIN}, which means the bot
     *     is not running
     */
    private @Nullable Request<BotRequest> waitFor(final Inbox<BotRequest> requests, final long id) {
        return waiting.until(
                        () -> requests.find(id).filter(row -> row.status().settled()),
                        ANSWER_WITHIN.plusSeconds(1),
                        Backoff.fixed(LOOK_EVERY))
                .orElse(null);
    }

    /**
     * The {@code unknown} field of the bot's own answer, split back into a list.
     *
     * An empty string is no keys rather than one empty key.
     */
    private static List<String> unknownIn(final @Nullable String result) {
        if (result == null || result.isBlank()) {
            return List.of();
        }
        try {
            final JsonElement parsed = Json.tree(result);
            if (!parsed.isJsonObject()) {
                return List.of();
            }
            final JsonElement unknown = parsed.getAsJsonObject().get("unknown");
            if (unknown == null
                    || !unknown.isJsonPrimitive()
                    || unknown.getAsString().isBlank()) {
                return List.of();
            }
            return COMMA.splitAsStream(unknown.getAsString()).toList();
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

    /** A dropped placeholder for every changed key that had one, checked against the packaged text. */
    private static List<String> warningsOf(
            final MessageBundle before, final String language, final Map<String, String> changes) {
        final List<String> warnings = new ArrayList<>();
        for (final Map.Entry<String, String> change : changes.entrySet()) {
            final String edited = change.getValue();
            if (edited == null) {
                // A reset has no new text to check placeholders against.
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

    // Finding the bundle

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

    // What goes over the wire

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
        // Gson drops a null map entry, so absence is the "no text" signal here.
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

    private static void putIfPresent(final Map<String, Object> row, final String key, final @Nullable String value) {
        if (value != null) {
            row.put(key, value);
        }
    }

    // What comes in

    private static JsonObject bodyOf(final String body) {
        try {
            return Json.tree(body == null ? "" : body).getAsJsonObject();
        } catch (final JsonSyntaxException | IllegalStateException | IllegalArgumentException e) {
            throw new BadRequestResponse("The body has to be a JSON object with a `changes` field.");
        }
    }

    /** The changes split by language, English first; {@code null} resets that language of that key. */
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
