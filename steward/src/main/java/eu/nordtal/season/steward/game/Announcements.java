package eu.nordtal.season.steward.game;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.common.language.Locales;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Writes an announcement of an admin as one request with a line per language into the bot's inbox. */
public final class Announcements {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();
    private static final StewardTexts.Steward.Said SAID =
            StewardTexts.TEXTS.steward().said();

    /** How many of the newest announcements {@link #open} reads back through a chain of retries. */
    private static final int CHAIN = 10;

    /** The longest message that Discord accepts. */
    public static final int MAX_LENGTH = 2000;

    private static final Pattern TAG = Pattern.compile("[a-z]{2,8}");

    private final Data data;
    private final Function<Context, DiscordAuth.Account> accounts;

    public Announcements(final Data data, final Function<Context, DiscordAuth.Account> accounts) {
        this.data = data;
        this.accounts = accounts;
    }

    /** The answer to {@code POST /api/announcements}: the line id of each language by tag. */
    public record AnnouncementsAsked(Map<String, String> ids) {}

    /** The answer to {@code GET /api/announcements/open}: each language of an announcement that did not fully go out. */
    public record OpenAnnouncement(List<OpenLine> lines) {}

    /** @param posted whether the bot posted {@code text} in that language */
    public record OpenLine(String language, String text, boolean posted) {}

    /** {@code GET /api/announcements/open} answers the languages of the newest announcement, if it did not fully go out. */
    public void open(final Context ctx) {
        ctx.json(new OpenAnnouncement(open(data.bot().recent(BotRequest.Announce.class, CHAIN))));
    }

    /**
     * Returns the lines of the newest announcement of an admin when some of its languages were not posted, so the form
     * can retry only those also after a reload. A retry carries only the missing languages, so the older requests it
     * retried fill in the rest: each one that failed for some language and holds every language of the one after it.
     * It returns nothing while the newest still runs or once it went out in full.
     */
    static List<OpenLine> open(final List<Request<BotRequest>> newestFirst) {
        final Map<String, OpenLine> lines = new LinkedHashMap<>();
        for (final Request<BotRequest> row : newestFirst) {
            if (!(row.payload() instanceof BotRequest.Announce announce)
                    || announce.message() != BotRequest.Announce.Message.WORDS) {
                continue;
            }
            if (row.status() == InboxStatus.PENDING || row.status() == InboxStatus.RUNNING) {
                if (lines.isEmpty()) {
                    return List.of();
                }
                break;
            }
            final List<String> languages = announce.lines().stream()
                    .map(BotRequest.Announce.Line::language)
                    .toList();
            final boolean someFailed = languages.stream().anyMatch(tag -> status(row, tag) != InboxStatus.DONE);
            if (!someFailed || !languages.containsAll(lines.keySet())) {
                break;
            }
            for (final BotRequest.Announce.Line line : announce.lines()) {
                lines.putIfAbsent(
                        line.language(),
                        new OpenLine(
                                line.language(),
                                Objects.requireNonNull(line.words(), "words"),
                                status(row, line.language()) == InboxStatus.DONE));
            }
        }
        return List.copyOf(lines.values());
    }

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
                        Schedule.within(CommandApi.PATIENCE),
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

    /** Returns the status of one language's line for the browser: a line the bot did not post failed. */
    static InboxStatus status(final Request<BotRequest> row, final String language) {
        return switch (row.status()) {
            case PENDING, RUNNING, FAILED, EXPIRED -> row.status();
            case DONE -> posted(row, language).orElse(true) ? InboxStatus.DONE : InboxStatus.FAILED;
            case REFUSED -> InboxStatus.FAILED;
            case CANCELLED -> InboxStatus.EXPIRED;
        };
    }

    /** Returns why the bot did not post the line of {@code language}, if it did not. */
    static Optional<MessageRef> result(final Request<BotRequest> row, final String language) {
        final Locale locale = Locales.parse(language);
        return posted(row, language)
                .filter(posted -> !posted)
                .map(posted -> SAID.notAnnounced(Locales.ownName(locale)));
    }

    private static Optional<Boolean> posted(final Request<BotRequest> row, final String language) {
        return row.answer(BotRequest.Announce.Answer.class).stream()
                .flatMap(answer -> answer.lines().stream())
                .filter(line -> line.language().equals(language))
                .findFirst()
                .map(BotRequest.Announce.Posted::posted);
    }
}
