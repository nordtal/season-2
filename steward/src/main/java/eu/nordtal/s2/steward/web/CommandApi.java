package eu.nordtal.s2.steward.web;

import com.google.gson.JsonObject;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.Actor;
import eu.nordtal.s2.database.DatabaseText;
import eu.nordtal.s2.database.audit.AuditLine;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.database.inbox.HungerGamesRequest;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.inbox.InboxStatus;
import eu.nordtal.s2.database.inbox.Request;
import eu.nordtal.s2.database.inbox.Schedule;
import eu.nordtal.s2.database.inbox.SmpRequest;
import eu.nordtal.s2.steward.auth.DiscordAuth;
import eu.nordtal.s2.steward.data.Data;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
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

    private static final Logger log = LoggerFactory.getLogger(CommandApi.class);

    /** How long the asker waits before the row is EXPIRED. */
    private static final Duration PATIENCE = Duration.ofMinutes(2);

    private final @Nullable Data data;

    private final Function<Context, DiscordAuth.Account> accounts;

    CommandApi(final @Nullable Data data, final Function<Context, DiscordAuth.Account> accounts) {
        this.data = data;
        this.accounts = accounts;
    }

    private Data data() {
        return Objects.requireNonNull(data, "no database - this route is not available without one");
    }

    /** Asks the SMP, journals who asked, and returns the name the browser polls. */
    String submit(final Context ctx, final SmpRequest request, final String what) {
        return "smp:" + submit(ctx, data().smp(), request, what);
    }

    /** Asks the Hunger Games server, journals who asked, and returns the name the browser polls. */
    String submit(final Context ctx, final HungerGamesRequest request, final String what) {
        return "hunger_games:" + submit(ctx, data().hungerGames(), request, what);
    }

    private <P> long submit(final Context ctx, final Inbox<P> inbox, final P request, final String what) {
        final DiscordAuth.Account who = accounts.apply(ctx);
        // One statement: a committed row with no journal line would run an action silently.
        final Request<P> asked = inbox.submit(
                request,
                Actor.person(DiscordId.of(who.id())),
                Schedule.within(PATIENCE),
                new AuditLine(
                        inbox.table().kindOf(request),
                        who.id(),
                        what,
                        null,
                        "asked by " + who.name() + " from Steward"));
        log.info("{} asked for {}", who.name(), what);
        return asked.id();
    }

    /** {@code GET /api/commands/{id}}: what became of it, the id being what {@link #submit} answered. */
    void outcome(final Context ctx) {
        final String[] name = ctx.pathParam("id").split(":", -1);
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("id", ctx.pathParam("id"));
        try {
            if (name.length == 3 && name[0].equals("announce")) {
                announced(Long.parseLong(name[1]), name[2], answer);
            } else if (name.length == 2 && name[0].equals("smp")) {
                settled(data().smp(), Long.parseLong(name[1]), ctx, answer);
            } else if (name.length == 2 && name[0].equals("hunger_games")) {
                settled(data().hungerGames(), Long.parseLong(name[1]), ctx, answer);
            } else {
                throw new BadRequestResponse(ctx.pathParam("id") + " is not a request.");
            }
        } catch (final NumberFormatException malformed) {
            throw new BadRequestResponse(ctx.pathParam("id") + " is not a request.");
        }
        ctx.json(answer);
    }

    /**
     * A server's request: its status, and its answer as English text, a refusal worded by the database bundle.
     *
     * A refusal also names its reason, which is what the browser branches on.
     */
    private static <P> void settled(
            final Inbox<P> inbox, final long id, final Context ctx, final Map<String, Object> answer) {
        final Request<P> row = inbox.find(id)
                .orElseThrow(() -> new NotFoundResponse("There is no request " + ctx.pathParam("id") + "."));
        answer.put("status", row.status().name());
        row.refusal().ifPresent(refusal -> answer.put("reason", refusal.reason().name()));
        final Optional<String> result = row.status() == InboxStatus.REFUSED
                ? row.refusal().map(refusal -> DatabaseText.english(refusal.message()))
                : row.status() == InboxStatus.DONE ? row.outcome(String.class) : failure(row);
        result.ifPresent(text -> answer.put("result", text));
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
    private void announced(final long id, final String language, final Map<String, Object> answer) {
        final Request<BotRequest> row = data().bot()
                .find(id)
                .filter(found -> found.payload() instanceof BotRequest.Announce)
                .orElseThrow(() -> new NotFoundResponse("There is no announcement " + id + "."));
        answer.put("status", Announcements.status(row.status()));
        Announcements.result(row, language).ifPresent(result -> answer.put("result", result));
    }
}
