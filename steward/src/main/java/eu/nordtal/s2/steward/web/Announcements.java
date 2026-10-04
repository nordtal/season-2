package eu.nordtal.s2.steward.web;

import static eu.nordtal.s2.database.AdminTexts.TEXTS;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.common.language.Locales;
import eu.nordtal.s2.database.DatabaseMessages;
import eu.nordtal.s2.database.audit.AuditLine;
import eu.nordtal.s2.database.audit.JournalAction;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.inbox.InboxStatus;
import eu.nordtal.s2.database.inbox.Request;
import eu.nordtal.s2.database.inbox.Schedule;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.steward.auth.DiscordAuth;
import eu.nordtal.s2.steward.data.Data;
import eu.nordtal.s2.steward.texts.RequestRefused;
import eu.nordtal.s2.steward.texts.StewardTexts;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Announcements an admin writes by hand: one request in the bot's inbox, a message per language, as the SMP sends.
 * The bot renders each; the list renders them here, from the same bundle with the same overrides.
 */
final class Announcements {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();
    private static final StewardTexts.Steward.Said SAID =
            StewardTexts.TEXTS.steward().said();

    /** Discord refuses a longer message. */
    static final int MAX_LENGTH = 2000;

    /** How long a request waits for the bot before it is given up on. */
    private static final Duration PATIENCE = Duration.ofMinutes(2);

    private static final Pattern TAG = Pattern.compile("[a-z]{2,8}");
    private static final int RECENT = 20;

    private final @Nullable Data data;
    private final Function<Context, DiscordAuth.Account> accounts;
    private final Messages announced;

    /** @param announced the database bundle in the network's languages, with the admins' overrides */
    Announcements(
            final @Nullable Data data,
            final Function<Context, DiscordAuth.Account> accounts,
            final Messages announced) {
        this.data = data;
        this.accounts = accounts;
        this.announced = Objects.requireNonNull(announced, "announced");
    }

    private Inbox<BotRequest> bot() {
        return Objects.requireNonNull(data, "no database - this route is not available without one")
                .bot();
    }

    /**
     * One language's line of an announcement; {@code result} is what became of it once the bot answered.
     *
     * @param id what {@code GET /api/commands/{id}} is asked with
     */
    public record Announcement(
            String id,
            String language,
            String text,
            Actor.Kind actorKind,
            String actorId,
            Instant requested,
            InboxStatus status,
            @Nullable MessageRef result) {}

    /** {@code GET /api/announcements}: the latest, by either sender, newest first. */
    public record RecentAnnouncements(List<Announcement> recent) {}

    /** {@code POST /api/announcements}: each language's line to follow, by its tag. */
    public record AnnouncementsAsked(Map<String, String> ids) {}

    /** {@code GET /api/announcements}: the latest, by either sender, newest first, one line per language. */
    void recent(final Context ctx) {
        final List<Announcement> recent = new ArrayList<>();
        for (final Request<BotRequest> row : bot().recent(BotRequest.Announce.class, RECENT)) {
            if (!(row.payload() instanceof BotRequest.Announce announcement)) {
                continue;
            }
            announcement
                    .messages()
                    .forEach((language, message) -> recent.add(new Announcement(
                            "announce:" + row.id() + ":" + language,
                            language,
                            announced.format(Locales.parse(language), message),
                            row.actor().kind(),
                            Objects.requireNonNullElse(row.actor().id(), ""),
                            row.requested(),
                            status(row.status()),
                            result(row, language).orElse(null))));
        }
        ctx.json(new RecentAnnouncements(recent));
    }

    /**
     * {@code POST /api/announcements} with {@code {texts: {<tag>: <text>, ...}}}: one request with every language.
     * Each language's message is the admin's words as written; nothing is written unless every entry carries text.
     */
    void send(final Context ctx) {
        final Map<String, String> checked = texts(ctx);
        final Map<String, MessageRef> messages = new LinkedHashMap<>();
        checked.forEach((tag, text) ->
                messages.put(tag, DatabaseMessages.MESSAGES.announcement().words(text)));
        final DiscordAuth.Account who = accounts.apply(ctx);
        final Request<BotRequest> asked = bot().submit(
                        new BotRequest.Announce(messages),
                        who.actor(),
                        Schedule.within(PATIENCE),
                        AuditLine.of(
                                JournalAction.ANNOUNCE,
                                who.actor(),
                                TEXTS.journal().announce(List.copyOf(checked.keySet()))));
        final Map<String, String> ids = new LinkedHashMap<>();
        checked.keySet().forEach(tag -> ids.put(tag, "announce:" + asked.id() + ":" + tag));
        ctx.status(202).json(new AnnouncementsAsked(ids));
    }

    private static Map<String, String> texts(final Context ctx) {
        final JsonObject body;
        try {
            body = Json.tree(ctx.body()).getAsJsonObject();
        } catch (final RuntimeException malformed) {
            throw new RequestRefused(400, ANSWER.notJson());
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
                throw new RequestRefused(400, ANSWER.emptyText(tag));
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
    static InboxStatus status(final InboxStatus status) {
        return switch (status) {
            case PENDING, RUNNING, DONE, FAILED, EXPIRED -> status;
            case REFUSED -> InboxStatus.FAILED;
            case CANCELLED -> InboxStatus.EXPIRED;
        };
    }

    /** Returns what became of one language's line once the bot has answered. */
    static Optional<MessageRef> result(final Request<BotRequest> row, final String language) {
        final @Nullable String posted = row.outcome(Map.class)
                .map(answer -> answer.get(language))
                .map(String::valueOf)
                .orElse(null);
        if (posted == null) {
            return Optional.empty();
        }
        return Optional.of(SAID.announced(BotRequest.Announce.POSTED.equals(posted), language));
    }
}
