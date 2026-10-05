package eu.nordtal.season.steward.web;

import com.google.gson.JsonObject;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.id.PlayerId;
import eu.nordtal.season.common.language.Locales;
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
import eu.nordtal.season.steward.api.MessagesApi;
import eu.nordtal.season.steward.auth.DiscordAuth;
import eu.nordtal.season.steward.data.Data;
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
 * Steward's actions on the game servers, asked for from a browser as typed requests in the servers' inboxes.
 * A request is named to the browser as {@code <target>:<id>}, or {@code announce:<id>:<language>} for one line;
 * the bot is a target only for an admin's preview of a text.
 */
final class CommandApi {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    private static final Logger log = LoggerFactory.getLogger(CommandApi.class);

    /** How long the asker waits before the row is EXPIRED. */
    private static final Duration PATIENCE = Duration.ofMinutes(2);

    private final @Nullable Data data;

    private final Function<Context, DiscordAuth.Account> accounts;
    private final Messages refusals;

    /** @param refusals the database bundle with the admins' overrides, which a server's refusal is worded in */
    CommandApi(
            final @Nullable Data data, final Function<Context, DiscordAuth.Account> accounts, final Messages refusals) {
        this.data = data;
        this.accounts = accounts;
        this.refusals = Objects.requireNonNull(refusals, "refusals");
    }

    private Data data() {
        return Objects.requireNonNull(data, "no database - this route is not available without one");
    }

    /** Asks the SMP, journals who asked, and returns the name the browser polls. */
    String submit(final Context ctx, final SmpRequest request, final JournalAction action, final MessageRef line) {
        return "smp:" + submit(ctx, data().smp(), request, action, line);
    }

    /** Asks the Hunger Games server, journals who asked, and returns the name the browser polls. */
    String submit(
            final Context ctx, final HungerGamesRequest request, final JournalAction action, final MessageRef line) {
        return "hunger_games:" + submit(ctx, data().hungerGames(), request, action, line);
    }

    private <P> long submit(
            final Context ctx,
            final Inbox<P> inbox,
            final P request,
            final JournalAction action,
            final MessageRef line) {
        final DiscordAuth.Account who = accounts.apply(ctx);
        // One statement: a committed row with no journal line would run an action silently.
        final Request<P> asked =
                inbox.submit(request, who.actor(), Schedule.within(PATIENCE), AuditLine.of(action, who.actor(), line));
        log.info("{} asked for {}", who.name(), inbox.table().kindOf(request));
        return asked.id();
    }

    /**
     * Asks for an admin's preview where it reaches them and returns the name the browser polls.
     * That is a direct message from the bot, or their linked player on the game server the roster has them on. A
     * preview changes nothing, so it is the one request without a journal line.
     */
    String preview(final Context ctx, final MessagePreview preview) {
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

    /**
     * What became of a request; {@code reason} is why a server refused, when it did.
     *
     * Out of time, PENDING means the target is down and RUNNING that it is stuck, so EXPIRED is a diagnosis.
     */
    public record CommandRun(
            String id,
            InboxStatus status,
            @Nullable MessageRef result,
            @Nullable String reason) {}

    /** {@code GET /api/commands/{id}}: what became of it, the id being what {@link #submit} answered. */
    void outcome(final Context ctx) {
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

    /**
     * A preview the bot was asked for, worded here once Discord delivered it.
     * The bot answers no words of its own; every other row of its inbox has a name of its own, or none.
     */
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

    /**
     * A request: its status, and its answer in its own words, a refusal worded by the database bundle.
     *
     * A refusal also names its reason, which is what the browser branches on.
     */
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

    /** A failed row's text: the server's own sentence, or the error a throwing handler left. */
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

    /** One language's line of an announcement: the request's status, and whether that language's text went out. */
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
