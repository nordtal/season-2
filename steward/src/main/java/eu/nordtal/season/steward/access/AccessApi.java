package eu.nordtal.season.steward.access;

import com.google.gson.JsonObject;
import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.InboxStatus;
import eu.nordtal.season.database.inbox.Request;
import eu.nordtal.season.database.inbox.Schedule;
import eu.nordtal.season.database.payment.Bookings;
import eu.nordtal.season.database.payment.PaymentRequest;
import eu.nordtal.season.steward.auth.DiscordAuth;
import eu.nordtal.season.steward.data.Data;
import eu.nordtal.season.steward.texts.RequestRefused;
import eu.nordtal.season.steward.texts.StewardTexts;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Writes grants, revocations, play time and unlinks into the bot's inbox, and books a payment by hand. */
public final class AccessApi {

    private static final StewardTexts.Steward.Answer ANSWER =
            StewardTexts.TEXTS.steward().answer();

    private static final Logger log = LoggerFactory.getLogger(AccessApi.class);

    /** The most days one grant gives. */
    static final int MOST_DAYS = 365;

    /** The time a request waits for the bot. */
    static final Duration PATIENCE = Duration.ofMinutes(2);

    /** The most play time in seconds, ten years. */
    static final long MOST_PLAYTIME_SECONDS = 10L * 365 * 24 * 3600;

    private final @Nullable Data data;

    private final Function<Context, DiscordAuth.Account> accounts;

    public AccessApi(final @Nullable Data data, final Function<Context, DiscordAuth.Account> accounts) {
        this.data = data;
        this.accounts = accounts;
    }

    private Data data() {
        return Objects.requireNonNull(data, "no database - this route is not available without one");
    }

    /** {@code POST /api/access/grant} with {@code {discordId, days}}. */
    public void grant(final Context ctx) {
        final Body ask = bodyOf(ctx);
        final DiscordId discordId = discordId(ask);
        if (ask.days == null || ask.days <= 0 || ask.days > MOST_DAYS) {
            throw new RequestRefused(400, ANSWER.grantDays(MOST_DAYS));
        }
        submit(ctx, new BotRequest.Grant(discordId, ask.days));
    }

    /** {@code POST /api/access/revoke} with {@code {discordId}}. */
    public void revoke(final Context ctx) {
        submit(ctx, new BotRequest.Revoke(discordId(bodyOf(ctx))));
    }

    /** {@code POST /api/access/unlink} with {@code {discordId}}. */
    public void unlink(final Context ctx) {
        submit(ctx, new BotRequest.Unlink(discordId(bodyOf(ctx))));
    }

    /** The result of a booking by hand. */
    public enum SettleOutcome {
        BOOKED,
        NOT_OPEN,
        UNKNOWN
    }

    /**
     * The answer of {@code POST /api/access/settle}, with {@code days} and {@code until} if booked, else {@code was}.
     */
    public record Settled(
            SettleOutcome outcome,
            @Nullable Integer days,
            @Nullable Instant until,
            @Nullable String was) {}

    /** A request in the bot's inbox with the typed answer of its kind, if it has one. */
    public record AccessRequestRun(
            String id,
            String kind,
            InboxStatus status,
            @Nullable JsonObject result) {}

    /** {@code POST /api/access/settle} with {@code {reference}} books an open payment request by hand as ordered. */
    public void settle(final Context ctx) {
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
            case Bookings.Booking.BelowMinimum below ->
                throw new IllegalStateException("a booking by hand books the order");
        }
    }

    /** {@code POST /api/people/{id}/playtime} with {@code {seconds}} sets the total play time. */
    public void playtime(final Context ctx) {
        final Body ask = bodyOf(ctx);
        if (ask.seconds == null || ask.seconds < 0 || ask.seconds > MOST_PLAYTIME_SECONDS) {
            throw new RequestRefused(400, ANSWER.playtimeRange(Duration.ofSeconds(MOST_PLAYTIME_SECONDS)));
        }
        submit(ctx, new BotRequest.SetPlaytime(discordId(ctx.pathParam("id")), ask.seconds));
    }

    /** {@code GET /api/access/requests/{id}} answers the status of a request. */
    public void outcome(final Context ctx) {
        final long id;
        try {
            id = Long.parseLong(ctx.pathParam("id"));
        } catch (final NumberFormatException e) {
            throw new RequestRefused(404, ANSWER.noRequest(ctx.pathParam("id")));
        }
        final Request<BotRequest> row =
                data().bot().find(id).orElseThrow(() -> new RequestRefused(404, ANSWER.noRequest(String.valueOf(id))));

        final Object answer = row.answer();
        ctx.json(new AccessRequestRun(
                String.valueOf(row.id()),
                row.kind(),
                row.status(),
                answer == null ? null : Json.gson().toJsonTree(answer).getAsJsonObject()));
    }

    private void submit(final Context ctx, final BotRequest request) {
        final DiscordAuth.Account who = accounts.apply(ctx);
        final Request<BotRequest> written =
                data().bot().submit(request, Actor.person(DiscordId.of(who.id())), Schedule.within(PATIENCE));
        log.info("{} asked the bot for {}", who.name(), request);

        ctx.status(202)
                .json(new AccessRequestRun(String.valueOf(written.id()), written.kind(), written.status(), null));
    }

    private static Body bodyOf(final Context ctx) {
        final Body body;
        try {
            body = ctx.bodyAsClass(Body.class);
        } catch (final RuntimeException malformed) {
            throw new RequestRefused(400, ANSWER.notJson());
        }
        if (body == null) throw new RequestRefused(400, ANSWER.empty());
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
