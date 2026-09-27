package eu.nordtal.s2.steward.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import eu.nordtal.s2.common.access.AccessRequest;
import eu.nordtal.s2.common.access.AccessRequestKind;
import eu.nordtal.s2.common.access.AccessRequestSource;
import eu.nordtal.s2.common.access.AccessRequests.NewAccessRequest;
import eu.nordtal.s2.steward.ui.auth.DiscordAuth;
import eu.nordtal.s2.steward.ui.data.Data;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Grants, revocations, play time, settling and unlinking, each written as an {@code access_request} row for the bot.
 *
 * Only the bot can carry out all parts of a grant, and it journals what it carries out.
 */
final class AccessApi {

    private static final Logger log = LoggerFactory.getLogger(AccessApi.class);

    /** The longest access one grant may give, in days; a longer period is two grants. */
    static final int MOST_DAYS = 365;

    /** The ceiling on a play time somebody may type: ten years of wall clock. */
    static final long MOST_PLAYTIME_SECONDS = 10L * 365 * 24 * 3600;

    private final @Nullable Data data;

    private final Function<Context, DiscordAuth.Account> accounts;

    AccessApi(final @Nullable Data data, final Function<Context, DiscordAuth.Account> accounts) {
        this.data = data;
        this.accounts = accounts;
    }

    private Data data() {
        return Objects.requireNonNull(data, "no database - this route is not available without one");
    }

    /** {@code POST /api/access/grant} with {@code {discordId, days}}. */
    void grant(final Context ctx) {
        final Body ask = bodyOf(ctx);
        final String discordId = discordId(ask);
        if (ask.days == null || ask.days <= 0 || ask.days > MOST_DAYS) {
            throw new BadRequestResponse(
                    "A grant is between 1 and " + MOST_DAYS + " days. A longer period is two grants.");
        }
        submit(ctx, AccessRequestKind.GRANT, discordId, (long) ask.days);
    }

    /** {@code POST /api/access/revoke} with {@code {discordId}}. */
    void revoke(final Context ctx) {
        submit(ctx, AccessRequestKind.REVOKE, discordId(bodyOf(ctx)), null);
    }

    /** {@code POST /api/access/unlink} with {@code {discordId}}. */
    void unlink(final Context ctx) {
        submit(ctx, AccessRequestKind.UNLINK, discordId(bodyOf(ctx)), null);
    }

    /** {@code POST /api/access/settle} with {@code {reference}}, a payment reference. */
    void settle(final Context ctx) {
        final Body ask = bodyOf(ctx);
        if (ask.reference == null || ask.reference.isBlank()) {
            throw new BadRequestResponse("reference is the payment to settle");
        }
        submit(ctx, AccessRequestKind.SETTLE, ask.reference.trim(), null);
    }

    /** {@code POST /api/people/{id}/playtime} with {@code {seconds}}, the new total. */
    void playtime(final Context ctx) {
        final Body ask = bodyOf(ctx);
        if (ask.seconds == null || ask.seconds < 0) {
            throw new BadRequestResponse("seconds is the new total, and is never negative");
        }
        if (ask.seconds > MOST_PLAYTIME_SECONDS) {
            throw new BadRequestResponse("seconds is at most " + MOST_PLAYTIME_SECONDS);
        }
        submit(ctx, AccessRequestKind.SET_PLAYTIME, ctx.pathParam("id"), ask.seconds);
    }

    /** {@code GET /api/access/requests/{id}}: what became of it. */
    void outcome(final Context ctx) {
        final long id;
        try {
            id = Long.parseLong(ctx.pathParam("id"));
        } catch (final NumberFormatException e) {
            throw new BadRequestResponse(ctx.pathParam("id") + " is not a request id.");
        }
        final AccessRequest row = data().accessRequests()
                .outcome(id)
                .orElseThrow(() -> new NotFoundResponse("There is no request " + id + "."));

        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("id", String.valueOf(row.id()));
        answer.put("kind", row.kind().name());
        answer.put("status", row.status().name());
        if (row.result() != null) answer.put("result", parsed(row.result()));
        ctx.json(answer);
    }

    private void submit(
            final Context ctx, final AccessRequestKind kind, final String subject, final @Nullable Long argument) {
        final DiscordAuth.Account who = accounts.apply(ctx);
        final NewAccessRequest request = argument == null
                ? NewAccessRequest.of(kind, subject, AccessRequestSource.STEWARD, who.id())
                : NewAccessRequest.of(kind, subject, argument, AccessRequestSource.STEWARD, who.id());
        final AccessRequest written = data().accessRequests().submit(request);
        log.info("{} asked the bot for {} {}{}", who.name(), kind, subject, argument == null ? "" : " " + argument);

        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("id", String.valueOf(written.id()));
        answer.put("kind", kind.name());
        answer.put("status", written.status().name());
        ctx.status(202).json(answer);
    }

    /** The bot's answer as an object; a row that does not parse is shown as text. */
    private static Object parsed(final String result) {
        try {
            final JsonElement element = JsonParser.parseString(result);
            return element.isJsonObject() ? element : Map.of("text", result);
        } catch (final JsonSyntaxException notJson) {
            return Map.of("text", result);
        }
    }

    private static Body bodyOf(final Context ctx) {
        final Body body;
        try {
            body = ctx.bodyAsClass(Body.class);
        } catch (final RuntimeException malformed) {
            throw new BadRequestResponse("The body is not the JSON this endpoint takes.");
        }
        if (body == null) throw new BadRequestResponse("The body is empty.");
        return body;
    }

    private static String discordId(final Body ask) {
        if (ask.discordId == null || ask.discordId.isBlank()) {
            throw new BadRequestResponse("discordId is whose access this is");
        }
        return ask.discordId.trim();
    }

    private static final class Body {
        private @Nullable String discordId;
        private @Nullable Integer days;
        private @Nullable Long seconds;
        private @Nullable String reference;
    }
}
