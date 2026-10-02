package eu.nordtal.s2.steward.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.database.inbox.InboxStatus;
import eu.nordtal.s2.database.inbox.Request;
import eu.nordtal.s2.database.inbox.Schedule;
import eu.nordtal.s2.database.payment.Bookings;
import eu.nordtal.s2.database.payment.PaymentRequest;
import eu.nordtal.s2.steward.auth.DiscordAuth;
import eu.nordtal.s2.steward.data.Data;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Grants, revocations, play time and unlinking, each written into the bot's inbox, and a booking by hand.
 *
 * Only the bot can carry out all parts of a grant, and it journals what it carries out. A booking is steward's own.
 */
final class AccessApi {

    private static final Logger log = LoggerFactory.getLogger(AccessApi.class);

    /** The longest access one grant may give, in days; a longer period is two grants. */
    static final int MOST_DAYS = 365;

    /** How long a request waits for the bot before it is given up on. */
    static final Duration PATIENCE = Duration.ofMinutes(2);

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
        final DiscordId discordId = discordId(ask);
        if (ask.days == null || ask.days <= 0 || ask.days > MOST_DAYS) {
            throw new BadRequestResponse(
                    "A grant is between 1 and " + MOST_DAYS + " days. A longer period is two grants.");
        }
        submit(ctx, new BotRequest.Grant(discordId, ask.days));
    }

    /** {@code POST /api/access/revoke} with {@code {discordId}}. */
    void revoke(final Context ctx) {
        submit(ctx, new BotRequest.Revoke(discordId(bodyOf(ctx))));
    }

    /** {@code POST /api/access/unlink} with {@code {discordId}}. */
    void unlink(final Context ctx) {
        submit(ctx, new BotRequest.Unlink(discordId(bodyOf(ctx))));
    }

    /** What a booking by hand came to: booked, not open any more, or no such reference. */
    public enum SettleOutcome {
        BOOKED,
        NOT_OPEN,
        UNKNOWN
    }

    /**
     * {@code POST /api/access/settle}'s answer; {@code days} and {@code until} when booked, {@code was} when not open.
     */
    public record Settled(
            SettleOutcome outcome,
            @Nullable Integer days,
            @Nullable Instant until,
            @Nullable String was) {}

    /** A request in the bot's inbox and, once it answered, what it said; a text answer is {@code {text}}. */
    public record AccessRequestRun(
            String id,
            String kind,
            InboxStatus status,
            @Nullable JsonObject result) {}

    /**
     * {@code POST /api/access/settle} with {@code {reference}}: books an open request by hand, at what it ordered.
     *
     * Booked here and now, in the transaction the poll books in too; the bot is told and only reacts.
     */
    void settle(final Context ctx) {
        final Body ask = bodyOf(ctx);
        if (ask.reference == null || ask.reference.isBlank()) {
            throw new BadRequestResponse("reference is the payment to settle");
        }
        final String reference = ask.reference.trim();
        final Optional<PaymentRequest> found = data().payments().byReference(reference);
        if (found.isEmpty()) {
            ctx.json(new Settled(SettleOutcome.UNKNOWN, null, null, null));
            return;
        }
        final DiscordAuth.Account who = accounts.apply(ctx);
        final Bookings.Booking booking = data().bookings()
                .book(
                        found.get().id(),
                        Bookings.Arrival.byHand(),
                        order -> Optional.of(order.asOrdered()),
                        Actor.person(DiscordId.of(who.id())));
        switch (booking) {
            case Bookings.Booking.Booked booked -> {
                log.info("{} booked {} by hand", who.name(), reference);
                ctx.json(new Settled(
                        SettleOutcome.BOOKED,
                        booked.told().days(),
                        booked.told().until(),
                        null));
            }
            case Bookings.Booking.NotOpen closed ->
                ctx.json(new Settled(
                        SettleOutcome.NOT_OPEN,
                        found.get().days(),
                        null,
                        data().payments()
                                .byId(found.get().id())
                                .map(row -> row.status().name())
                                .orElse(found.get().status().name())));
            // The order itself is the settlement, so there is always one.
            case Bookings.Booking.BelowMinimum below ->
                throw new IllegalStateException("a booking by hand books the order");
        }
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
        submit(ctx, new BotRequest.SetPlaytime(discordId(ctx.pathParam("id")), ask.seconds));
    }

    /** {@code GET /api/access/requests/{id}}: what became of it. */
    void outcome(final Context ctx) {
        final long id;
        try {
            id = Long.parseLong(ctx.pathParam("id"));
        } catch (final NumberFormatException e) {
            throw new BadRequestResponse(ctx.pathParam("id") + " is not a request id.");
        }
        final Request<BotRequest> row =
                data().bot().find(id).orElseThrow(() -> new NotFoundResponse("There is no request " + id + "."));

        final String outcome = row.outcome();
        ctx.json(new AccessRequestRun(
                String.valueOf(row.id()), row.kind(), row.status(), outcome == null ? null : parsed(outcome)));
    }

    private void submit(final Context ctx, final BotRequest request) {
        final DiscordAuth.Account who = accounts.apply(ctx);
        final Request<BotRequest> written =
                data().bot().submit(request, Actor.person(DiscordId.of(who.id())), Schedule.within(PATIENCE));
        log.info("{} asked the bot for {}", who.name(), request);

        ctx.status(202)
                .json(new AccessRequestRun(String.valueOf(written.id()), written.kind(), written.status(), null));
    }

    /** The bot's answer as an object; a row that does not parse is shown as text. */
    private static JsonObject parsed(final String result) {
        try {
            final JsonElement element = Json.tree(result);
            if (element.isJsonObject()) {
                return element.getAsJsonObject();
            }
        } catch (final JsonSyntaxException notJson) {
            // Shown as text, below.
        }
        final JsonObject text = new JsonObject();
        text.addProperty("text", result);
        return text;
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

    private static DiscordId discordId(final Body ask) {
        if (ask.discordId == null || ask.discordId.isBlank()) {
            throw new BadRequestResponse("discordId is whose access this is");
        }
        return discordId(ask.discordId);
    }

    private static DiscordId discordId(final String id) {
        try {
            return DiscordId.of(id.trim());
        } catch (final IllegalArgumentException notAnId) {
            throw new BadRequestResponse(id + " is not a Discord id.");
        }
    }

    private static final class Body {
        private @Nullable String discordId;
        private @Nullable Integer days;
        private @Nullable Long seconds;
        private @Nullable String reference;
    }
}
