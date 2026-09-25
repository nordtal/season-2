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
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Granting, revoking, play time, settling and unlinking - asked of the bot, never done here.
 *
 * <p>A grant is four things at once: the row, the Discord role, a direct message in the recipient's
 * own language and a line in the admin channel. Only the bot holds a Discord session, so when this
 * process wrote the access tables itself the other three silently did not happen, and somebody
 * granted access from a browser was never told. Every write here is therefore one
 * {@code access_request} row, carried out by the bot exactly as {@code /access} in Discord is.</p>
 *
 * <p><b>No journal line is written here.</b> The bot journals what it carries out, naming the admin
 * this row names; a second line from this side would be the same change recorded twice.</p>
 *
 * <p>Every write answers 202 with the row's id, and {@code GET /api/access/requests/{id}} is what
 * became of it - the shape {@link CommandApi} already has, for the same reason: the work happens in
 * another process, and the only thing shared with it is PostgreSQL.</p>
 */
final class AccessApi {

    private static final Logger log = LoggerFactory.getLogger(AccessApi.class);

    /**
     * The longest access one grant may give, in days.
     *
     * <p>A season. Once this interface is the one door, this number is the only limit there is,
     * and 3650 - the old ceiling - was a decade of free access one keystroke away from 365. A longer
     * period is two grants.</p>
     */
    static final int MOST_DAYS = 365;

    /**
     * The ceiling on a play time somebody may type: ten years of wall clock, which nobody reaches
     * and a slipped digit does.
     */
    static final long MOST_PLAYTIME_SECONDS = 10L * 365 * 24 * 3600;

    private final Data data;

    /** Who is asking. The same seam {@code StewardUi} uses, so a test can stand in front of it. */
    private final Function<Context, DiscordAuth.Account> accounts;

    AccessApi(final @NotNull Data data,
              final @NotNull Function<Context, DiscordAuth.Account> accounts) {
        this.data = data;
        this.accounts = accounts;
    }

    /** {@code POST /api/access/grant} - {@code {discordId, days}}. */
    void grant(final @NotNull Context ctx) {
        final Body ask = bodyOf(ctx);
        final String discordId = discordId(ask);
        if (ask.days == null || ask.days <= 0 || ask.days > MOST_DAYS) {
            throw new BadRequestResponse("A grant is between 1 and " + MOST_DAYS
                    + " days. A longer period is two grants.");
        }
        submit(ctx, AccessRequestKind.GRANT, discordId, (long) ask.days);
    }

    /** {@code POST /api/access/revoke} - {@code {discordId}}. */
    void revoke(final @NotNull Context ctx) {
        submit(ctx, AccessRequestKind.REVOKE, discordId(bodyOf(ctx)), null);
    }

    /** {@code POST /api/access/unlink} - {@code {discordId}}. */
    void unlink(final @NotNull Context ctx) {
        submit(ctx, AccessRequestKind.UNLINK, discordId(bodyOf(ctx)), null);
    }

    /** {@code POST /api/access/settle} - {@code {reference}}, a payment reference. */
    void settle(final @NotNull Context ctx) {
        final Body ask = bodyOf(ctx);
        if (ask.reference == null || ask.reference.isBlank()) {
            throw new BadRequestResponse("reference is the payment to settle");
        }
        submit(ctx, AccessRequestKind.SETTLE, ask.reference.trim(), null);
    }

    /** {@code POST /api/people/{id}/playtime} - {@code {seconds}}, the new total. */
    void playtime(final @NotNull Context ctx) {
        final Body ask = bodyOf(ctx);
        if (ask.seconds == null || ask.seconds < 0) {
            throw new BadRequestResponse("seconds is the new total, and is never negative");
        }
        if (ask.seconds > MOST_PLAYTIME_SECONDS) {
            // A century of play time is a typo, and the column is a bigint that would take it
            // without complaint.
            throw new BadRequestResponse("seconds is at most " + MOST_PLAYTIME_SECONDS);
        }
        submit(ctx, AccessRequestKind.SET_PLAYTIME, ctx.pathParam("id"), ask.seconds);
    }

    /** {@code GET /api/access/requests/{id}} - what became of it. */
    void outcome(final @NotNull Context ctx) {
        final long id;
        try {
            id = Long.parseLong(ctx.pathParam("id"));
        } catch (final NumberFormatException e) {
            throw new BadRequestResponse(ctx.pathParam("id") + " is not a request id.");
        }
        final AccessRequest row = data.accessRequests().outcome(id)
                .orElseThrow(() -> new NotFoundResponse("There is no request " + id + "."));

        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("id", String.valueOf(row.id()));
        answer.put("kind", row.kind().name());
        answer.put("status", row.status().name());
        if (row.result() != null) answer.put("result", parsed(row.result()));
        ctx.json(answer);
    }

    // ---------------------------------------------------------------------------------------

    private void submit(final Context ctx, final AccessRequestKind kind, final String subject,
                        final Long argument) {
        final DiscordAuth.Account who = accounts.apply(ctx);
        final NewAccessRequest request = argument == null
                ? NewAccessRequest.of(kind, subject, AccessRequestSource.STEWARD, who.id())
                : NewAccessRequest.of(kind, subject, argument, AccessRequestSource.STEWARD,
                        who.id());
        final AccessRequest written = data.accessRequests().submit(request);
        log.info("{} asked the bot for {} {}{}", who.name(), kind, subject,
                argument == null ? "" : " " + argument);

        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("id", String.valueOf(written.id()));
        answer.put("kind", kind.name());
        answer.put("status", written.status().name());
        ctx.status(202).json(answer);
    }

    /**
     * The bot's answer as an object, not as a string inside one. It writes that column itself and
     * escapes it; a row that does not parse is still shown, as the text it holds.
     */
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

    /** Every field any of the five takes. Each route reads the ones it needs. */
    private static final class Body {
        private String discordId;
        private Integer days;
        private Long seconds;
        private String reference;
    }
}
