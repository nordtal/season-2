package eu.nordtal.s2.steward.api;

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
import eu.nordtal.s2.internalapi.agent.AgentClient;
import eu.nordtal.s2.internalapi.agent.AgentWire;
import eu.nordtal.s2.internalapi.agent.MessageArg;
import eu.nordtal.s2.internalapi.agent.MessageBundle;
import eu.nordtal.s2.internalapi.agent.MessageEntry;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import io.javalin.http.NotFoundResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The routes over the message bundles, which steward-agent reads out of the jars and saves beside them. */
public final class MessagesApi {

    private static final Logger log = LoggerFactory.getLogger(MessagesApi.class);

    /** Asks a running service to re-read what it can while it runs, narrowed so a test can pass a lambda. */
    @FunctionalInterface
    public interface Reloader {

        /**
         * Asks {@code service} for a reload through its inbox and waits for the answer.
         *
         * @return the answer, or empty when the service did not answer in time
         * @throws IllegalArgumentException for a service that has no inbox
         */
        Optional<Reloaded> reload(String service);
    }

    /**
     * A service's answer to a reload.
     *
     * @param applied whether it re-read everything
     * @param text its own words, for the page
     */
    public record Reloaded(boolean applied, String text) {}

    /** The bundles a running Minecraft service re-reads on a reload, by identity; any other needs a restart. */
    private static final Set<String> RELOADABLE =
            Set.of("smp/smp", "hunger-games/hunger-games", "limbo/limbo", "proxy/proxy");

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

    private final AgentClient agent;
    private final @Nullable Inbox<BotRequest> inbox;
    private final Reloader reloader;

    /**
     * Builds the API.
     *
     * @param inbox the bot's inbox, or {@code null} without a database, which makes {@link #reload}
     *     ask for a restart
     * @param reloader asks a Minecraft service to re-read a saved bundle
     */
    public MessagesApi(
            final AgentClient agent,
            final @Nullable Inbox<BotRequest> inbox,
            final Reloader reloader,
            final Waiting waiting) {
        this.waiting = waiting;
        this.agent = agent;
        this.inbox = inbox;
        this.reloader = reloader;
    }

    /** {@code GET /api/messages}: every bundle found, without opening a single jar. */
    public void list(final Context ctx) {
        ctx.json(agent.bundles().stream().map(MessagesApi::describe).toList());
    }

    /** {@code GET /api/messages/<bundle>}: one bundle, packaged text and override side by side. */
    public void one(final Context ctx) {
        final AgentWire.BundleRef location = locate(ctx);
        ctx.json(document(location, agent.bundle(location.service(), location.module())));
    }

    /**
     * {@code PUT /api/messages/<bundle>}: applies changes to both languages' override files at once.
     *
     * A {@code null} resets a key; a dropped placeholder is a warning, an undeclared one a 400 that writes nothing.
     */
    public void save(final Context ctx) {
        final AgentWire.BundleRef location = locate(ctx);
        if (!location.writable()) {
            throw new ForbiddenResponse(identityOf(location) + " is mounted read-only in this"
                    + " container, so this interface cannot save a change to it.");
        }
        final AgentWire.SavedBundle saved =
                agent.saveBundle(location.service(), location.module(), changesOf(bodyOf(ctx.body())));
        final Map<String, Object> answer = document(location, saved.bundle());
        answer.put("warnings", saved.warnings());
        answer.put("reload", reload(location));
        ctx.json(answer);
    }

    /**
     * Asks {@code service} to re-read what {@link #RELOADABLE} names by {@code identity}; never restarts anything.
     *
     * Answers {@code RESTART_REQUIRED}, {@code APPLIED} when the service re-read it, or {@code NO_ANSWER}.
     */
    static Map<String, Object> reload(
            final Reloader reloader,
            final String identity,
            final String service,
            final String name,
            final String file) {
        final Map<String, Object> answer = new LinkedHashMap<>();
        if (!RELOADABLE.contains(identity)) {
            answer.put("status", "RESTART_REQUIRED");
            answer.put(
                    "message",
                    "Saved. Nothing reloads " + name + " live; "
                            + (service.isEmpty() ? "it" : service)
                            + " only reads it again at its next restart, which stays a click of its own.");
            return answer;
        }
        final Optional<Reloaded> reloaded;
        try {
            reloaded = reloader.reload(service);
        } catch (final IllegalArgumentException e) {
            log.warn("{} is reloadable, but {} cannot be asked: {}", file, service, e.getMessage());
            answer.put("status", "RESTART_REQUIRED");
            answer.put("message", "Saved. " + e.getMessage());
            return answer;
        }
        if (reloaded.isEmpty()) {
            log.warn("{} was saved but {} did not answer the reload", file, service);
            answer.put("status", "NO_ANSWER");
            answer.put(
                    "message",
                    "Saved, but " + service + " did not answer. The change is on disk and takes effect once that"
                            + " service reads it again.");
        } else if (reloaded.get().applied()) {
            answer.put("status", "APPLIED");
            answer.put(
                    "message",
                    "Saved, and " + service + " re-read it: " + reloaded.get().text());
        } else {
            answer.put("status", "NO_ANSWER");
            answer.put(
                    "message",
                    "Saved, but " + service + " did not take all of it: "
                            + reloaded.get().text());
        }
        return answer;
    }

    /**
     * Asks the service that owns a just-saved bundle to re-read it, answered like the static {@code reload}.
     *
     * The bot's answer adds {@code unknown}: the keys its override file declares that the bundle does not know.
     */
    private Map<String, Object> reload(final AgentWire.BundleRef location) {
        if (!RELOADABLE_SERVICE.equals(location.service())) {
            final Map<String, Object> answer = reload(
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

    // Finding the bundle

    private AgentWire.BundleRef locate(final Context ctx) {
        final String asked = ctx.pathParam("bundle");
        return agent.bundles().stream()
                .filter(location -> identityOf(location).equals(asked))
                .findFirst()
                .orElseThrow(() -> new NotFoundResponse("There is no message bundle called " + asked + "."));
    }

    /** How a bundle is named in a URL: {@code <service>/<module>}, or just {@code <service>}. */
    private static String identityOf(final AgentWire.BundleRef location) {
        return location.module().isEmpty() ? location.service() : location.service() + "/" + location.module();
    }

    // What goes over the wire

    private static Map<String, Object> describe(final AgentWire.BundleRef location) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("service", location.service());
        row.put("module", location.module());
        row.put("path", identityOf(location));
        row.put("writable", location.writable());
        return row;
    }

    private static Map<String, Object> document(final AgentWire.BundleRef location, final MessageBundle bundle) {
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

    /** The changes in the order given; a {@code null} text resets that language of that key. */
    private static List<AgentWire.TextChange> changesOf(final JsonObject body) {
        final JsonElement changes = body.get("changes");
        if (changes == null || !changes.isJsonObject()) {
            throw new BadRequestResponse("`changes` has to be an object of key to {\"en\": text,"
                    + " \"de\": text}, where null resets that language.");
        }
        final List<AgentWire.TextChange> all = new ArrayList<>();
        for (final Map.Entry<String, JsonElement> change :
                changes.getAsJsonObject().entrySet()) {
            if (!change.getValue().isJsonObject()) {
                throw new BadRequestResponse(
                        change.getKey() + " has to be an object of language to" + " text, like {\"en\": \"...\"}.");
            }
            for (final Map.Entry<String, JsonElement> text :
                    change.getValue().getAsJsonObject().entrySet()) {
                if (!"en".equals(text.getKey()) && !"de".equals(text.getKey())) {
                    throw new BadRequestResponse("A language has to be \"en\" or \"de\", not " + text.getKey() + ".");
                }
                final JsonElement value = text.getValue();
                all.add(new AgentWire.TextChange(
                        change.getKey(),
                        text.getKey(),
                        value == null || value.isJsonNull() ? null : value.getAsString()));
            }
        }
        if (all.isEmpty()) {
            throw new BadRequestResponse("`changes` is empty - there is nothing to save.");
        }
        return all;
    }
}
