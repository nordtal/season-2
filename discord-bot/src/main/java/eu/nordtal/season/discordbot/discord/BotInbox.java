package eu.nordtal.season.discordbot.discord;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.Outcome;
import eu.nordtal.season.database.inbox.Request;
import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.messages.MessageRef;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Runs the requests of the bot's inbox, because only this process has a JDA session.
 *
 * An access change answers with its kind's typed answer, which Steward shows. The inbox fails a request that throws.
 */
public final class BotInbox implements Inbox.Handler<BotRequest> {

    private final AccessChanges effects;
    private final BiPredicate<String, MessageRef> announce;
    private final Predicate<BotRequest.PostAlert> alert;
    private final Consumer<BotRequest.PaymentBooked> paid;
    private final Predicate<BotRequest.PreviewMessage> preview;

    /**
     * @param announce renders a message in one language, posts it to that language's announcement channel and
     *     returns whether it was posted
     * @param alert posts an alert to the admin channel and returns whether it was posted
     * @param paid sends the payer a message about the booked payment
     * @param preview sends an admin the previewed text or document as a direct message and returns whether Discord
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
            case BotRequest.Grant grant ->
                Outcome.done(new BotRequest.Grant.Answer(effects.grant(grant.person(), grant.days(), by)));
            case BotRequest.Revoke revoke ->
                Outcome.done(new BotRequest.Revoke.Answer(effects.revoke(revoke.person(), by)));
            case BotRequest.Unlink unlink ->
                Outcome.done(new BotRequest.Unlink.Answer(effects.unlink(unlink.person(), by)));
            case BotRequest.PaymentBooked booked -> {
                paid.accept(booked);
                yield Outcome.done(null);
            }
            case BotRequest.SetPlaytime playtime -> {
                effects.setPlaytime(playtime.person(), playtime.seconds(), by);
                yield Outcome.done(null);
            }
            case BotRequest.Announce announcement -> Outcome.done(post(announcement));
            case BotRequest.PostAlert posted -> Outcome.done(Map.of("posted", String.valueOf(alert.test(posted))));
            // Steward words the refusal.
            case BotRequest.PreviewMessage previewed ->
                preview.test(previewed) ? Outcome.done(null) : Outcome.refused(ServerRefusal.NOT_DELIVERED.with());
        };
    }

    /** Posts the message of every language and answers per language whether it was posted. */
    private Map<String, String> post(final BotRequest.Announce announcement) {
        final Map<String, String> posted = new LinkedHashMap<>();
        announcement
                .messages()
                .forEach((tag, message) -> posted.put(
                        tag,
                        announce.test(tag, message) ? BotRequest.Announce.POSTED : BotRequest.Announce.NOT_POSTED));
        return posted;
    }
}
