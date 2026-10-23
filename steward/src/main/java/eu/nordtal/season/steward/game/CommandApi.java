package eu.nordtal.season.steward.game;

import com.google.gson.JsonObject;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.DocumentPreview;
import eu.nordtal.season.database.inbox.HungerGamesRequest;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.InboxStatus;
import eu.nordtal.season.database.inbox.MessagePreview;
import eu.nordtal.season.database.inbox.Request;
import eu.nordtal.season.database.inbox.Schedule;
import eu.nordtal.season.database.inbox.SmpRequest;
import eu.nordtal.season.database.online.OnlinePlayer;
import eu.nordtal.season.internalapi.agent.Topology;
import eu.nordtal.season.messages.MessageRef;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.spec.Display;
import eu.nordtal.season.steward.auth.DiscordAuth;
import eu.nordtal.season.steward.data.Data;
import eu.nordtal.season.steward.messages.MessagesApi;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.http.Context;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the browser's actions on the game servers as typed requests into the servers' inboxes.
 * A request is named {@code <target>:<id>}, or {@code announce:<id>:<language>} for one announcement line.
 * The bot is a target for the preview of a text or a document, and its access requests are followed here too.
 */
public final class CommandApi {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    private static final Logger log = LoggerFactory.getLogger(CommandApi.class);

    /** The time a request in a server's inbox waits to be picked up before it expires. */
    public static final Duration PATIENCE = Duration.ofMinutes(2);

    private final Data data;

    private final Function<Context, DiscordAuth.Account> accounts;
    private final Messages refusals;

    /** @param refusals the database bundle that words a server's refusal */
    public CommandApi(final Data data, final Function<Context, DiscordAuth.Account> accounts, final Messages refusals) {
        this.data = data;
        this.accounts = accounts;
        this.refusals = Objects.requireNonNull(refusals, "refusals");
    }

    /** Writes the request to the SMP inbox with a journal line and returns its name. */
    String submit(
            final Context ctx,
            final SmpRequest request,
            final JournalAction action,
            final @Nullable JournalDetail detail) {
        return "smp:" + submit(ctx, data.smp(), request, new Line(action, null, detail));
    }

    /** Writes the request about {@code subject} to the SMP inbox, journaled as about them, and returns its name. */
    String submit(
            final Context ctx,
            final DiscordId subject,
            final SmpRequest request,
            final JournalAction action,
            final JournalDetail detail) {
        return "smp:" + submit(ctx, data.smp(), request, new Line(action, subject, detail));
    }

    /** The journal line of a request, written with the asker as its actor. */
    private record Line(
            JournalAction action,
            @Nullable DiscordId subject,
            @Nullable JournalDetail detail) {}

    /** Writes the request to the Hunger Games inbox with a journal line and returns its name. */
    String submit(
            final Context ctx,
            final HungerGamesRequest request,
            final JournalAction action,
            final @Nullable JournalDetail detail) {
        return "hunger_games:" + submit(ctx, data.hungerGames(), request, new Line(action, null, detail));
    }

    private <P> long submit(final Context ctx, final Inbox<P> inbox, final P request, final Line line) {
        final DiscordAuth.Account who = accounts.apply(ctx);
        final Request<P> asked = inbox.submit(
                request,
                who.actor(),
                Schedule.within(PATIENCE),
                new AuditLine(line.action(), who.actor(), line.subject(), null, line.detail()));
        log.info("{} asked for {}", who.name(), inbox.table().kindOf(request));
        return asked.id();
    }

    /** Writes the request for an admin's preview of a text and returns its name. */
    public String preview(final Context ctx, final MessagePreview preview) {
        return preview(
                ctx,
                preview.shown(),
                admin -> new BotRequest.PreviewMessage(admin, preview),
                player -> new SmpRequest.PreviewMessage(player, preview),
                player -> new HungerGamesRequest.PreviewMessage(player, preview));
    }

    /** Writes the request for an admin's preview of a drafted document and returns its name. */
    public String preview(final Context ctx, final DocumentPreview preview) {
        return preview(
                ctx,
                preview.shown(),
                admin -> new BotRequest.PreviewDocument(admin, preview),
                player -> new SmpRequest.PreviewDocument(player, preview),
                player -> new HungerGamesRequest.PreviewDocument(player, preview));
    }

    /**
     * Writes the request for an admin's preview shown at {@code shown} and returns its name.
     * The target is a direct message of the bot or the linked player on their game server. The request has no
     * journal line.
     */
    private String preview(
            final Context ctx,
            final Display shown,
            final Function<DiscordId, BotRequest> bot,
            final Function<PlayerId, SmpRequest> smp,
            final Function<PlayerId, HungerGamesRequest> games) {
        final DiscordAuth.Account who = accounts.apply(ctx);
        final DiscordId admin = DiscordId.of(who.id());
        if (MessagesApi.PreviewTarget.of(shown) == MessagesApi.PreviewTarget.DISCORD) {
            return "bot:" + ask(who, data.bot(), bot.apply(admin));
        }
        final UUID linked = data.access()
                .linkedMinecraftAccount(admin)
                .orElseThrow(() -> new RequestRefused(409, ANSWER.previewUnlinked()));
        final String server = data.roster().current().stream()
                .filter(online -> online.uuid().equals(linked))
                .map(OnlinePlayer::subject)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("");
        final PlayerId player = PlayerId.of(linked);
        return switch (server) {
            case Topology.SMP -> "smp:" + ask(who, data.smp(), smp.apply(player));
            case Topology.HUNGER_GAMES -> "hunger_games:" + ask(who, data.hungerGames(), games.apply(player));
            default -> throw new RequestRefused(409, ANSWER.previewOffline());
        };
    }

    private <P> long ask(final DiscordAuth.Account who, final Inbox<P> inbox, final P request) {
        final Request<P> asked = inbox.submit(request, who.actor(), Schedule.within(PATIENCE));
        log.info("{} asked for {}", who.name(), inbox.table().kindOf(request));
        return asked.id();
    }

    /**
     * The status of a request.
     * {@code reason} is the reason of a server refusal, {@code answer} the typed answer of a bot request that has one.
     */
    public record CommandRun(
            String id,
            InboxStatus status,
            @Nullable MessageRef result,
            @Nullable String reason,
            @Nullable JsonObject answer) {}

    /** {@code GET /api/commands/{id}} answers the status of the request that {@code submit} named. */
    public void outcome(final Context ctx) {
        final String id = ctx.pathParam("id");
        final String[] name = id.split(":", -1);
        try {
            if (name.length == 3 && name[0].equals("announce")) {
                ctx.json(announced(id, Long.parseLong(name[1]), name[2]));
            } else if (name.length == 2 && name[0].equals("smp")) {
                ctx.json(settled(data.smp(), id, Long.parseLong(name[1])));
            } else if (name.length == 2 && name[0].equals("hunger_games")) {
                ctx.json(settled(data.hungerGames(), id, Long.parseLong(name[1])));
            } else if (name.length == 2 && name[0].equals("bot")) {
                ctx.json(sent(id, Long.parseLong(name[1])));
            } else {
                throw new RequestRefused(404, ANSWER.noRequest(id));
            }
        } catch (final NumberFormatException malformed) {
            throw new RequestRefused(404, ANSWER.noRequest(id));
        }
    }

    private <P> CommandRun settled(final Inbox<P> inbox, final String name, final long id) {
        return settled(name, inbox.find(id).orElseThrow(() -> new RequestRefused(404, ANSWER.noRequest(name))));
    }

    /**
     * Returns the status of a bot request with its typed answer.
     * A done preview carries the sent text; an announcement is followed by language instead.
     */
    private CommandRun sent(final String name, final long id) {
        final Request<BotRequest> row = data.bot()
                .find(id)
                .filter(found -> !(found.payload() instanceof BotRequest.Announce))
                .orElseThrow(() -> new RequestRefused(404, ANSWER.noRequest(name)));
        final CommandRun run = settled(name, row);
        final boolean preview = row.payload() instanceof BotRequest.PreviewMessage
                || row.payload() instanceof BotRequest.PreviewDocument;
        final Object answer = row.answer();
        return new CommandRun(
                name,
                run.status(),
                preview && row.status() == InboxStatus.DONE
                        ? StewardTexts.TEXTS.steward().said().previewSent()
                        : run.result(),
                run.reason(),
                answer == null ? null : Json.gson().toJsonTree(answer).getAsJsonObject());
    }

    /** Returns the status of a request and the words and reason of a refusal. */
    private <P> CommandRun settled(final String name, final Request<P> row) {
        return new CommandRun(
                name,
                row.status(),
                row.refusal()
                        .map(refusal -> refusals.format(Locales.DEFAULT, refusal.message()))
                        .map(StewardTexts.TEXTS.steward().said()::words)
                        .orElse(null),
                row.refusal().map(refusal -> refusal.reason().name()).orElse(null),
                null);
    }

    /** Returns the status of the announcement and the result for one language. */
    private CommandRun announced(final String name, final long id, final String language) {
        final Request<BotRequest> row = data.bot()
                .find(id)
                .filter(found -> found.payload() instanceof BotRequest.Announce)
                .orElseThrow(() -> new RequestRefused(404, ANSWER.noAnnouncement(id)));
        return new CommandRun(
                name,
                Announcements.status(row.status()),
                Announcements.result(row, language).orElse(null),
                null,
                null);
    }
}
