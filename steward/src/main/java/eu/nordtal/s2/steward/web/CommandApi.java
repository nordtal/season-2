package eu.nordtal.s2.steward.web;

import com.google.gson.JsonObject;
import eu.nordtal.s2.common.language.Locales;
import eu.nordtal.s2.database.audit.AuditLine;
import eu.nordtal.s2.database.audit.JournalAction;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.database.inbox.HungerGamesRequest;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.inbox.InboxStatus;
import eu.nordtal.s2.database.inbox.Request;
import eu.nordtal.s2.database.inbox.Schedule;
import eu.nordtal.s2.database.inbox.SmpRequest;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.steward.auth.DiscordAuth;
import eu.nordtal.s2.steward.data.Data;
import eu.nordtal.s2.steward.texts.RequestRefused;
import eu.nordtal.s2.steward.texts.StewardTexts;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Steward's actions on the game servers, asked for from a browser as typed requests in the servers' inboxes.
 * A request is named to the browser as {@code <target>:<id>}, or {@code announce:<id>:<language>} for one line.
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
            } else {
                throw new BadRequestResponse(id + " is not a request.");
            }
        } catch (final NumberFormatException malformed) {
            throw new BadRequestResponse(id + " is not a request.");
        }
    }

    /**
     * A server's request: its status, and its answer in its own words, a refusal worded by the database bundle.
     *
     * A refusal also names its reason, which is what the browser branches on.
     */
    private <P> CommandRun settled(final Inbox<P> inbox, final String name, final long id) {
        final Request<P> row = inbox.find(id).orElseThrow(() -> new RequestRefused(404, ANSWER.noRequest(name)));
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
