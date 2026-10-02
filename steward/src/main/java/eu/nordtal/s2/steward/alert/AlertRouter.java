package eu.nordtal.s2.steward.alert;

import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.common.json.Json;
import eu.nordtal.s2.database.alert.Alert;
import eu.nordtal.s2.database.alert.AlertBook;
import eu.nordtal.s2.database.alert.AlertChannel;
import eu.nordtal.s2.database.alert.AlertType;
import eu.nordtal.s2.database.alert.RaisedAlert;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.inbox.Schedule;
import eu.nordtal.s2.steward.push.PushSender;
import eu.nordtal.s2.steward.push.PushSubscriptions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one way an alert leaves Steward: to the browsers and into the admin channel of every admin who wants it.
 *
 * A row is marked routed before it is sent, so an alert reaches a channel at most once and never twice.
 */
public final class AlertRouter {

    /** How long the bot has to post an alert before the request expires unclaimed. */
    static final Duration BOT_PATIENCE = Duration.ofHours(1);

    private static final Logger log = LoggerFactory.getLogger(AlertRouter.class);

    private final AlertBook book;
    private final AlertPreferences preferences;
    private final Supplier<List<DiscordId>> admins;
    private final Inbox<BotRequest> bot;
    private final @Nullable PushSubscriptions subscriptions;
    private final @Nullable PushSender sender;
    private final String publicUrl;

    /**
     * @param sender null without a VAPID keypair, which leaves the admin channel as the only way out
     * @param publicUrl Steward's own address, which a post in the admin channel links to
     */
    public AlertRouter(
            final AlertBook book,
            final AlertPreferences preferences,
            final Supplier<List<DiscordId>> admins,
            final Inbox<BotRequest> bot,
            final @Nullable PushSubscriptions subscriptions,
            final @Nullable PushSender sender,
            final String publicUrl) {
        this.book = Objects.requireNonNull(book, "book");
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.admins = Objects.requireNonNull(admins, "admins");
        this.bot = Objects.requireNonNull(bot, "bot");
        this.subscriptions = subscriptions;
        this.sender = sender;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    /** Routes every alert not yet routed; a failure is logged, since the next signal tries again. */
    public void route() {
        final List<RaisedAlert> raised;
        try {
            raised = book.claimUnrouted();
        } catch (final RuntimeException failure) {
            log.warn("Could not read the alerts to route", failure);
            return;
        }
        if (raised.isEmpty()) {
            return;
        }
        final AlertPreferences.Chosen chosen = preferences.all();
        final List<PushSubscriptions.Subscription> browsers =
                subscriptions == null || sender == null ? List.of() : subscriptions.all();
        final List<DiscordId> everyAdmin = admins.get();
        for (final RaisedAlert one : raised) {
            push(one.alert(), chosen, browsers);
            post(one.alert(), chosen, everyAdmin);
        }
    }

    /** {@link PushSender.Result} outside the push package: it went, that browser is gone, or it failed. */
    public enum Delivery {
        SENT,
        /** The push service says that subscription no longer exists; the row has been removed. */
        GONE,
        FAILED
    }

    /** Sends one sample of {@code type} to one browser, whatever its admin chose, or null without a VAPID keypair. */
    public @Nullable Delivery sendSample(final PushSubscriptions.Subscription subscription, final AlertType type) {
        if (sender == null || subscriptions == null) {
            return null;
        }
        final PushSender.Result result = send(subscription, payloadOf(sample(type)));
        if (result == PushSender.Result.EXPIRED) {
            subscriptions.expired(subscription.endpoint());
            return Delivery.GONE;
        }
        return result == PushSender.Result.SENT ? Delivery.SENT : Delivery.FAILED;
    }

    private void push(
            final Alert alert,
            final AlertPreferences.Chosen chosen,
            final List<PushSubscriptions.Subscription> browsers) {
        final String payload = payloadOf(alert);
        for (final PushSubscriptions.Subscription subscription : browsers) {
            if (!chosen.wants(subscription.discordId(), alert.type(), AlertChannel.PUSH)) {
                continue;
            }
            switch (send(subscription, payload)) {
                case SENT -> Objects.requireNonNull(subscriptions).touchSent(subscription.endpoint());
                // A dead subscription (404/410) is removed, or it keeps failing forever.
                case EXPIRED -> Objects.requireNonNull(subscriptions).expired(subscription.endpoint());
                case FAILED -> log.warn("push to {} failed (not a 404/410)", subscription.endpoint());
            }
        }
    }

    /** Asks the bot to post it when one admin wants it there, mentioning those admins unless it is an all-clear. */
    private void post(final Alert alert, final AlertPreferences.Chosen chosen, final List<DiscordId> everyAdmin) {
        final List<DiscordId> wanting = everyAdmin.stream()
                .filter(admin -> chosen.wants(admin, alert.type(), AlertChannel.DISCORD))
                .toList();
        if (wanting.isEmpty()) {
            return;
        }
        final String link = publicUrl + alert.path();
        final String detail = alert.detail().isBlank() ? link : alert.detail() + "\n" + link;
        try {
            bot.submit(
                    new BotRequest.PostAlert(
                            alert.level(),
                            alert.title(),
                            detail,
                            alert.level() == Alert.Level.OK ? List.of() : wanting),
                    Actor.STEWARD,
                    Schedule.within(BOT_PATIENCE));
        } catch (final RuntimeException failure) {
            log.warn("Could not ask the bot to post the alert '{}'", alert.title(), failure);
        }
    }

    private PushSender.Result send(final PushSubscriptions.Subscription subscription, final String payload) {
        try {
            return Objects.requireNonNull(sender).send(subscription, payload);
        } catch (final RuntimeException failure) {
            log.warn("push to {} failed: {}", subscription.endpoint(), failure.getMessage());
            return PushSender.Result.FAILED;
        }
    }

    /** The push body {@code sw.js} reads. */
    static String payloadOf(final Alert alert) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", alert.type().key());
        body.put("level", alert.level().key());
        body.put("title", alert.title());
        body.put("subject", alert.subject());
        body.put("path", alert.path());
        return Json.encode(body);
    }

    /** A real-looking alert of that type, to show what one looks like. */
    static Alert sample(final AlertType type) {
        return switch (type) {
            case SERVICE -> sampleOf(type, Alert.Level.DOWN, "smp", "smp is not running", "/services/smp");
            case BACKUP ->
                sampleOf(type, Alert.Level.DOWN, "database dump", "There is no database dump", "/operations/backups");
            case DISK -> sampleOf(type, Alert.Level.WARN, "disk", "The disk is 91 % full", "/");
            case MEMORY -> sampleOf(type, Alert.Level.WARN, "memory", "Memory is 93 % used", "/");
            case DRIFT ->
                sampleOf(type, Alert.Level.WARN, "registry", "The images were not compared", "/operations/updates");
            case RUN -> sampleOf(type, Alert.Level.DOWN, "update", "The update run failed", "/operations/updates");
            case PAYMENT -> sampleOf(type, Alert.Level.DOWN, "payment", "A payment needs a look", "/payments");
            case BOT -> sampleOf(type, Alert.Level.WARN, "access role", "The access role was not given", "/access");
        };
    }

    private static Alert sampleOf(
            final AlertType type,
            final Alert.Level level,
            final String subject,
            final String title,
            final String path) {
        return new Alert(type, level, subject, title, "", path);
    }
}
