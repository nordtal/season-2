package eu.nordtal.season.steward.game;

import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.announce.AnnouncementBook;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.InboxStatus;
import eu.nordtal.season.database.inbox.Request;
import eu.nordtal.season.database.inbox.Schedule;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.document.Document;
import eu.nordtal.season.steward.auth.DiscordAuth;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.http.Context;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * An admin's announcements: drafts with a channel per language, sent as one request to the bot.
 * A send of a sent one posts again only the languages the bot could not post.
 */
public final class Announcements {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();
    private static final StewardTexts.Steward.Said SAID =
            StewardTexts.TEXTS.steward().said();

    /** The namespace of the bot's document that a new announcement starts from and is checked against. */
    public static final String TEMPLATE_NAMESPACE = "discord";

    /** The name of that document. */
    public static final String TEMPLATE_NAME = "announcement";

    private static final Pattern TAG = Pattern.compile("[a-z]{2,8}");
    private static final Pattern CHANNEL = Pattern.compile("[0-9]{1,32}");

    private final AnnouncementBook book;
    private final Function<Context, DiscordAuth.Account> accounts;
    private final Consumer<Document> check;

    /** @param check refuses a document that the bot's announcement document could not be saved as */
    public Announcements(
            final AnnouncementBook book,
            final Function<Context, DiscordAuth.Account> accounts,
            final Consumer<Document> check) {
        this.book = book;
        this.accounts = accounts;
        this.check = check;
    }

    /** The answer to {@code GET /api/announcements}: the drafts and the sent ones, the newest first. */
    public record AnnouncementList(List<AnnouncementView> drafts, List<AnnouncementView> sent) {}

    /** @param sent when it was first sent, absent for a draft */
    public record AnnouncementView(
            long id,
            Document document,
            Instant changed,
            @Nullable Instant sent,
            List<AnnouncementTarget> targets) {}

    /** One language of an announcement, the channel it goes out in and how far it got. */
    public record AnnouncementTarget(String language, String channel, AnnouncementBook.Delivery delivery) {}

    /** The body of a new or changed draft: its document and the channel of each language, by tag. */
    public record AnnouncementDraft(Document document, Map<String, String> channels) {}

    /** The answer to a new draft. */
    public record AnnouncementCreated(long id) {}

    /** The answer to a send: the line id of each language it posts, by tag, empty when all are out already. */
    public record AnnouncementsAsked(Map<String, String> ids) {}

    /** {@code GET /api/announcements} answers every announcement. */
    public void list(final Context ctx) {
        final List<AnnouncementView> all =
                book.all().stream().map(Announcements::view).toList();
        ctx.json(new AnnouncementList(
                all.stream().filter(one -> one.sent() == null).toList(),
                all.stream().filter(one -> one.sent() != null).toList()));
    }

    /** {@code POST /api/announcements} writes a new draft. */
    public void create(final Context ctx) {
        final AnnouncementDraft draft = draftOf(ctx);
        ctx.status(201).json(new AnnouncementCreated(book.create(draft.document(), draft.channels())));
    }

    /** {@code PUT /api/announcements/{id}} replaces a draft. A sent one is a 409. */
    public void change(final Context ctx) {
        final long id = idOf(ctx);
        final AnnouncementBook.Announcement found =
                book.find(id).orElseThrow(() -> new RequestRefused(404, ANSWER.noAnnouncement(id)));
        if (found.sent() != null) {
            throw new RequestRefused(409, ANSWER.announcementSent());
        }
        final AnnouncementDraft draft = draftOf(ctx);
        if (!book.change(id, draft.document(), draft.channels())) {
            throw refusal(id);
        }
        ctx.status(204);
    }

    /** {@code DELETE /api/announcements/{id}} deletes a draft. A sent one is a 409. */
    public void delete(final Context ctx) {
        final long id = idOf(ctx);
        if (!book.delete(id)) {
            throw refusal(id);
        }
        ctx.status(204);
    }

    /**
     * {@code POST /api/announcements/{id}/send} asks the bot to post every language not out yet.
     * The request and its journal line are one transaction.
     */
    public void send(final Context ctx) {
        final long id = idOf(ctx);
        final AnnouncementBook.Announcement announcement =
                book.find(id).orElseThrow(() -> new RequestRefused(404, ANSWER.noAnnouncement(id)));
        if (announcement.targets().isEmpty()) {
            throw new RequestRefused(400, ANSWER.noAnnouncementChannel());
        }
        final AnnouncementBook.Sent sent = book.send(
                        id, accounts.apply(ctx).actor(), Schedule.within(CommandApi.PATIENCE))
                .orElseThrow(() -> new RequestRefused(404, ANSWER.noAnnouncement(id)));
        final Map<String, String> ids = new LinkedHashMap<>();
        sent.request()
                .ifPresent(request -> sent.languages().forEach(tag -> ids.put(tag, "announce:" + request + ":" + tag)));
        ctx.status(202).json(new AnnouncementsAsked(ids));
    }

    private RequestRefused refusal(final long id) {
        return book.find(id).isPresent()
                ? new RequestRefused(409, ANSWER.announcementSent())
                : new RequestRefused(404, ANSWER.noAnnouncement(id));
    }

    private static AnnouncementView view(final AnnouncementBook.Announcement announcement) {
        return new AnnouncementView(
                announcement.id(),
                announcement.document(),
                announcement.changed(),
                announcement.sent(),
                announcement.targets().stream()
                        .map(target -> new AnnouncementTarget(target.language(), target.channel(), target.delivery()))
                        .toList());
    }

    private static long idOf(final Context ctx) {
        try {
            return Long.parseLong(ctx.pathParam("id"));
        } catch (final NumberFormatException malformed) {
            throw new RequestRefused(404, ANSWER.noRequest(ctx.pathParam("id")));
        }
    }

    /** Reads a draft's body, refusing a malformed one, a bad tag or channel, and a document the bot refuses. */
    private AnnouncementDraft draftOf(final Context ctx) {
        final AnnouncementDraft draft;
        try {
            draft = Json.decode(ctx.body(), AnnouncementDraft.class);
        } catch (final RuntimeException malformed) {
            throw new RequestRefused(400, ANSWER.notJson());
        }
        if (draft == null || draft.document() == null || draft.channels() == null) {
            throw new RequestRefused(400, ANSWER.empty());
        }
        for (final Map.Entry<String, String> channel : draft.channels().entrySet()) {
            if (!TAG.matcher(channel.getKey()).matches()
                    || channel.getValue() == null
                    || !CHANNEL.matcher(channel.getValue()).matches()) {
                throw new RequestRefused(400, ANSWER.notJson());
            }
        }
        check.accept(draft.document());
        return draft;
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
        final List<BotRequest.Announce.Posted> lines = row.payload() instanceof BotRequest.PostAnnouncement
                ? row.answer(BotRequest.PostAnnouncement.Answer.class)
                        .map(BotRequest.PostAnnouncement.Answer::targets)
                        .orElse(List.of())
                : row.answer(BotRequest.Announce.Answer.class)
                        .map(BotRequest.Announce.Answer::lines)
                        .orElse(List.of());
        return lines.stream()
                .filter(line -> line.language().equals(language))
                .findFirst()
                .map(BotRequest.Announce.Posted::posted);
    }
}
