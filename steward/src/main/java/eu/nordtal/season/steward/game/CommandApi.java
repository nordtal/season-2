package eu.nordtal.season.steward.game;

import com.google.gson.JsonObject;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.inbox.BotRequest;
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
import eu.nordtal.season.steward.auth.DiscordAuth;
import eu.nordtal.season.steward.data.Data;
import eu.nordtal.season.steward.messages.MessagesApi;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.http.Context;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the browser's actions on the game servers as typed requests into the servers' inboxes.
 * A request is named {@code <target>:<id>}, or {@code announce:<id>:<language>} for one announcement line.
 * The bot is a target for the preview of a text only.
 */
public final class CommandApi {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    private static final Logger log = LoggerFactory.getLogger(CommandApi.class);

    /** The time after which a request expires. */
    private static final Duration PATIENCE = Duration.ofMinutes(2);

    private final @Nullable Data data;

    private final Function<Context, DiscordAuth.Account> accounts;
    private final Messages refusals;

    /** @param refusals the database bundle that words a server's refusal */
    public CommandApi(
            final @Nullable Data data, final Function<Context, DiscordAuth.Account> accounts, final Messages refusals) {
        this.data = data;
        this.accounts = accounts;
        this.refusals = Objects.requireNonNull(refusals, "refusals");
    }

    private Data data() {
        return Objects.requireNonNull(data, "no database - this route is not available without one");
    }

    /** Writes the request to the SMP inbox with a journal line and returns its name. */
    String submit(
            final Context ctx,
            final SmpRequest request,
            final JournalAction action,
            final @Nullable JournalDetail detail) {
        return "smp:" + submit(ctx, data().smp(), request, new Line(action, null, detail));
    }

    /** Writes the request about {@code subject} to the SMP inbox, journaled as about them, and returns its name. */
    String submit(
            final Context ctx,
            final DiscordId subject,
            final SmpRequest request,
            final JournalAction action,
            final JournalDetail detail) {
        return "smp:" + submit(ctx, data().smp(), request, new Line(action, subject, detail));
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
        return "hunger_games:" + submit(ctx, data().hungerGames(), request, new Line(action, null, detail));
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

    /**
     * Writes the request for an admin's preview and returns its name.
     * The target is a direct message of the bot or the linked player on their game server. The request has no
     * journal line.
     */
    public String preview(final Context ctx, final MessagePreview preview) {
        final DiscordAuth.Account who = accounts.apply(ctx);
        final DiscordId admin = DiscordId.of(who.id());
        if (MessagesApi.PreviewTarget.of(preview.shown()) == MessagesApi.PreviewTarget.DISCORD) {
            return "bot:" + ask(who, data().bot(), new BotRequest.PreviewMessage(admin, preview));
        }
        final UUID linked = data().access()
                .linkedMinecraftAccount(admin)
                .orElseThrow(() -> new RequestRefused(409, ANSWER.previewUnlinked()));
        final String server = data().roster().current().stream()
                .filter(online -> online.uuid().equals(linked))
                .map(OnlinePlayer::subject)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("");
        final PlayerId player = PlayerId.of(linked);
        return switch (server) {
            case Topology.SMP -> "smp:" + ask(who, data().smp(), new SmpRequest.PreviewMessage(player, preview));
            case Topology.HUNGER_GAMES ->
                "hunger_games:"
                        + ask(who, data().hungerGames(), new HungerGamesRequest.PreviewMessage(player, preview));
            default -> throw new RequestRefused(409, ANSWER.previewOffline());
        };
    }

    private <P> long ask(final DiscordAuth.Account who, final Inbox<P> inbox, final P request) {
        final Request<P> asked = inbox.submit(request, who.actor(), Schedule.within(PATIENCE));
        log.info("{} asked for {}", who.name(), inbox.table().kindOf(request));
        return asked.id();
    }

    /** The status of a request. {@code reason} is the reason of a server refusal. */
    public record CommandRun(
            String id,
            InboxStatus status,
            @Nullable MessageRef result,
            @Nullable String reason) {}

    /** {@code GET /api/commands/{id}} answers the status of the request that {@code submit} named. */
    public void outcome(final Context ctx) {
        final String id = ctx.pathParam("id");
        final String[] name = id.split(":", -1);
        try {
            if (name.length == 3 && name[0].equals("announce")) {
                ctx.json(announced(id, Long.parseLong(name[1]), name[2]));
            } else if (name.length == 2 && name[0].equals("smp")) {
                ctx.json(settled(data().smp(), id, Long.parseLong(name[1])));
            } else if (name.length == 2 && name[0].equals("hunger_games")) {
                ctx.json(settled(data().hungerGames(), id, Long.parseLong(name[1])));
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

    /** Returns the status of a bot preview, with the sent text once the row is done. */
    private CommandRun sent(final String name, final long id) {
        final Request<BotRequest> row = data().bot()
                .find(id)
                .filter(found -> found.payload() instanceof BotRequest.PreviewMessage)
                .orElseThrow(() -> new RequestRefused(404, ANSWER.noRequest(name)));
        final CommandRun run = settled(name, row);
        return row.status() == InboxStatus.DONE
                ? new CommandRun(
                        name, run.status(), StewardTexts.TEXTS.steward().said().previewSent(), null)
                : run;
    }

    /** Returns the status of a request, its result and the reason of a refusal. */
    private <P> CommandRun settled(final String name, final Request<P> row) {
        final Optional<String> result = row.status() == InboxStatus.REFUSED
                ? row.refusal().map(refusal -> refusals.format(Locales.DEFAULT, refusal.message()))
                : row.status() == InboxStatus.DONE ? row.outcome(String.class) : failure(row);
        return new CommandRun(
                name,
                row.status(),
                result.map(StewardTexts.TEXTS.steward().said()::words).orElse(null),
                row.refusal().map(refusal -> refusal.reason().name()).orElse(null));
    }

    /** Returns the failure text of a failed row. */
    private static <P> Optional<String> failure(final Request<P> row) {
        if (row.status() != InboxStatus.FAILED) {
            return Optional.empty();
        }
        try {
            return row.outcome(String.class);
        } catch (final RuntimeException notText) {
            return row.outcome(JsonObject.class)
                    .map(error -> error.has("error") ? error.get("error").getAsString() : error.toString());
        }
    }

    /** Returns the status of the announcement and the result for one language. */
    private CommandRun announced(final String name, final long id, final String language) {
        final Request<BotRequest> row = data().bot()
                .find(id)
                .filter(found -> found.payload() instanceof BotRequest.Announce)
                .orElseThrow(() -> new RequestRefused(404, ANSWER.noAnnouncement(id)));
        return new CommandRun(
                name,
                Announcements.status(row.status()),
                Announcements.result(row, language).orElse(null),
                null);
    }
}
