package eu.nordtal.s2.steward.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.audit.AuditLine;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.inbox.InboxStatus;
import eu.nordtal.s2.database.inbox.Request;
import eu.nordtal.s2.database.inbox.Schedule;
import eu.nordtal.s2.steward.ui.auth.DiscordAuth;
import eu.nordtal.s2.steward.ui.data.Data;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Announcements an admin writes by hand: one request in the bot's inbox with a text per language, as the SMP sends. */
final class Announcements {

    /** Discord refuses a longer message. */
    static final int MAX_LENGTH = 2000;

    /** How long a request waits for the bot before it is given up on. */
    private static final Duration PATIENCE = Duration.ofMinutes(2);

    private static final Pattern TAG = Pattern.compile("[a-z]{2,8}");
    private static final int RECENT = 20;

    private final @Nullable Data data;
    private final Function<Context, DiscordAuth.Account> accounts;

    Announcements(final @Nullable Data data, final Function<Context, DiscordAuth.Account> accounts) {
        this.data = data;
        this.accounts = accounts;
    }

    private Inbox<BotRequest> bot() {
        return Objects.requireNonNull(data, "no database - this route is not available without one")
                .bot();
    }

    /** {@code GET /api/announcements}: the latest, by either sender, newest first, one line per language. */
    void recent(final Context ctx) {
        final List<Map<String, Object>> recent = new ArrayList<>();
        for (final Request<BotRequest> row : bot().recent(BotRequest.Announce.class, RECENT)) {
            if (!(row.payload() instanceof BotRequest.Announce announcement)) {
                continue;
            }
            announcement.texts().forEach((language, text) -> {
                final Map<String, Object> line = new LinkedHashMap<>();
                line.put("id", "announce:" + row.id() + ":" + language);
                line.put("language", language);
                line.put("text", text);
                line.put("actorKind", row.actor().kind().name());
                line.put("actorId", row.actor().id() == null ? "" : row.actor().id());
                line.put("requested", row.requested().toString());
                line.put("status", status(row.status()));
                result(row, language).ifPresent(result -> line.put("result", result));
                recent.add(line);
            });
        }
        ctx.json(Map.of("recent", recent));
    }

    /**
     * {@code POST /api/announcements} with {@code {texts: {<tag>: <text>, ...}}}: one request carrying every language.
     * Nothing is written unless every entry carries text; the answer names each language's line to poll.
     */
    void send(final Context ctx) {
        final Map<String, String> checked = texts(ctx);
        final DiscordAuth.Account who = accounts.apply(ctx);
        final Request<BotRequest> asked = bot().submit(
                        new BotRequest.Announce(checked),
                        Actor.person(DiscordId.of(who.id())),
                        Schedule.within(PATIENCE),
                        new AuditLine(
                                "ANNOUNCE",
                                who.id(),
                                null,
                                null,
                                "asked by " + who.name() + " from the web interface"));
        final Map<String, String> ids = new LinkedHashMap<>();
        checked.keySet().forEach(tag -> ids.put(tag, "announce:" + asked.id() + ":" + tag));
        ctx.status(202).json(Map.of("ids", ids));
    }

    private static Map<String, String> texts(final Context ctx) {
        final JsonObject body;
        try {
            body = Json.tree(ctx.body()).getAsJsonObject();
        } catch (final RuntimeException malformed) {
            throw new BadRequestResponse("The body is not the JSON this endpoint takes.");
        }
        final JsonElement texts = body.get("texts");
        if (texts == null || !texts.isJsonObject() || texts.getAsJsonObject().isEmpty()) {
            throw new BadRequestResponse("texts is one text per language.");
        }
        final Map<String, String> checked = new LinkedHashMap<>();
        for (final Map.Entry<String, JsonElement> entry :
                texts.getAsJsonObject().entrySet()) {
            final String tag = entry.getKey();
            if (!TAG.matcher(tag).matches()) {
                throw new BadRequestResponse(tag + " is not a language tag.");
            }
            final JsonElement text = entry.getValue();
            if (text == null || !text.isJsonPrimitive() || text.getAsString().isBlank()) {
                throw new BadRequestResponse("The " + tag + " text is empty.");
            }
            final String stripped = text.getAsString().strip();
            if (stripped.length() > MAX_LENGTH) {
                throw new BadRequestResponse(
                        "The " + tag + " text is longer than Discord takes (" + MAX_LENGTH + " characters).");
            }
            checked.put(tag, stripped);
        }
        return checked;
    }

    /** Returns the status the browser knows, in which a line that went nowhere is a failure. */
    static String status(final InboxStatus status) {
        return switch (status) {
            case PENDING, RUNNING, DONE, FAILED, EXPIRED -> status.name();
            case REFUSED -> InboxStatus.FAILED.name();
            case CANCELLED -> InboxStatus.EXPIRED.name();
        };
    }

    /** Returns what became of one language's line once the bot has answered. */
    static Optional<String> result(final Request<BotRequest> row, final String language) {
        final @Nullable String posted = row.outcome(Map.class)
                .map(answer -> answer.get(language))
                .map(String::valueOf)
                .orElse(null);
        if (posted == null) {
            return Optional.empty();
        }
        return Optional.of(
                BotRequest.Announce.POSTED.equals(posted)
                        ? "Posted to the " + language + " announcement channel."
                        : "Not posted: " + language + " has no announcement channel the bot can write to.");
    }
}
