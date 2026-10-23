package eu.nordtal.season.steward.game;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.InboxStatus;
import eu.nordtal.season.database.inbox.Request;
import eu.nordtal.season.database.inbox.Schedule;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.steward.auth.DiscordAuth;
import eu.nordtal.season.steward.data.Data;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Writes an announcement of an admin as one request with a line per language into the bot's inbox. */
public final class Announcements {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();
    private static final StewardTexts.Steward.Said SAID =
            StewardTexts.TEXTS.steward().said();

    /** The longest message that Discord accepts. */
    public static final int MAX_LENGTH = 2000;

    /** The time a request waits for the bot. */
    private static final Duration PATIENCE = Duration.ofMinutes(2);

    private static final Pattern TAG = Pattern.compile("[a-z]{2,8}");

    private final Data data;
    private final Function<Context, DiscordAuth.Account> accounts;

    public Announcements(final Data data, final Function<Context, DiscordAuth.Account> accounts) {
        this.data = data;
        this.accounts = accounts;
    }

    /** The answer to {@code POST /api/announcements}: the line id of each language by tag. */
    public record AnnouncementsAsked(Map<String, String> ids) {}

    /**
     * {@code POST /api/announcements} with {@code {texts: {<tag>: <text>, ...}}} writes one request.
     * It writes nothing unless every entry has text.
     */
    public void send(final Context ctx) {
        final Map<String, String> checked = texts(ctx);
        final DiscordAuth.Account who = accounts.apply(ctx);
        final Request<BotRequest> asked = data.bot()
                .submit(
                        BotRequest.Announce.words(checked),
                        who.actor(),
                        Schedule.within(PATIENCE),
                        AuditLine.of(
                                JournalAction.ANNOUNCE,
                                who.actor(),
                                new JournalDetail.Announced(List.copyOf(checked.keySet()))));
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
                throw new RequestRefused(400, ANSWER.tooLong(tag, MAX_LENGTH));
            }
            checked.put(tag, stripped);
        }
        return checked;
    }

    /** Returns the status for the browser. */
    static InboxStatus status(final InboxStatus status) {
        return switch (status) {
            case PENDING, RUNNING, DONE, FAILED, EXPIRED -> status;
            case REFUSED -> InboxStatus.FAILED;
            case CANCELLED -> InboxStatus.EXPIRED;
        };
    }

    /** Returns the result for one language once the bot has answered. */
    static Optional<MessageRef> result(final Request<BotRequest> row, final String language) {
        return row.answer(BotRequest.Announce.Answer.class).stream()
                .flatMap(answer -> answer.lines().stream())
                .filter(line -> line.language().equals(language))
                .findFirst()
                .map(line -> SAID.announced(line.posted(), language));
    }
}
