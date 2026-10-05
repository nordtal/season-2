package eu.nordtal.season.discordbot.discord;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.Outcome;
import eu.nordtal.season.database.inbox.Request;
import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.messages.MessageRef;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Carries out what the bot's inbox holds, since only this process holds a JDA session.
 * The answer is a flat object of strings, which Steward shows; a request that throws is failed by the inbox.
 */
public final class BotInbox implements Inbox.Handler<BotRequest> {

    private final AccessChanges effects;
    private final BiPredicate<String, MessageRef> announce;
    private final Predicate<BotRequest.PostAlert> alert;
    private final Consumer<BotRequest.PaymentBooked> paid;
    private final Predicate<BotRequest.PreviewMessage> preview;

    /**
     * @param announce renders a message in one language, posts it into that language's announcement channel and
     *     answers whether it went out
     * @param alert posts an alert into the admin channel and answers whether there was one
     * @param paid tells a payer what steward booked for them
     * @param preview sends an admin a text they are trying as a direct message and answers whether Discord
     *     delivered it
     */
    public BotInbox(
            final AccessChanges effects,
            final BiPredicate<String, MessageRef> announce,
            final Predicate<BotRequest.PostAlert> alert,
            final Consumer<BotRequest.PaymentBooked> paid,
            final Predicate<BotRequest.PreviewMessage> preview) {
        this.effects = Objects.requireNonNull(effects, "effects");
        this.announce = Objects.requireNonNull(announce, "announce");
        this.alert = Objects.requireNonNull(alert, "alert");
        this.paid = Objects.requireNonNull(paid, "paid");
        this.preview = Objects.requireNonNull(preview, "preview");
    }

    @Override
    public Outcome handle(final Request<BotRequest> request) {
        final Actor by = request.actor();
        return switch (request.payload()) {
            case BotRequest.Grant grant -> {
                final Instant until = effects.grant(grant.person(), grant.days(), by);
                yield done("until", until.toString());
            }
            case BotRequest.Revoke revoke -> done("revoked", String.valueOf(effects.revoke(revoke.person(), by)));
            case BotRequest.Unlink unlink -> done("unlinked", String.valueOf(effects.unlink(unlink.person(), by)));
            case BotRequest.PaymentBooked booked -> {
                paid.accept(booked);
                yield done("told", booked.person().value());
            }
            case BotRequest.SetPlaytime playtime -> {
                effects.setPlaytime(playtime.person(), playtime.seconds(), by);
                yield done("seconds", String.valueOf(playtime.seconds()));
            }
            case BotRequest.Announce announcement -> Outcome.done(post(announcement));
            case BotRequest.PostAlert posted -> done("posted", String.valueOf(alert.test(posted)));
            // Steward words what it shows; a preview Discord did not deliver is the admin's to fix, so it is refused.
            case BotRequest.PreviewMessage previewed ->
                preview.test(previewed) ? Outcome.done(null) : Outcome.refused(ServerRefusal.NOT_DELIVERED.with());
        };
    }

    /** Posts every language's message and answers, per language, whether it went out; a missing channel is no fault. */
    private Map<String, String> post(final BotRequest.Announce announcement) {
        final Map<String, String> posted = new LinkedHashMap<>();
        announcement
                .messages()
                .forEach((tag, message) -> posted.put(
                        tag,
                        announce.test(tag, message) ? BotRequest.Announce.POSTED : BotRequest.Announce.NOT_POSTED));
        return posted;
    }

    /** Returns a done outcome whose answer is the flat object {@code key, value, key, value}. */
    private static Outcome done(final @Nullable String... pairs) {
        final Map<String, @Nullable String> answer = new LinkedHashMap<>();
        for (int at = 0; at < pairs.length; at += 2) {
            answer.put(Objects.requireNonNull(pairs[at], "key"), pairs[at + 1]);
        }
        return Outcome.done(answer);
    }
}
