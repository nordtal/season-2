package eu.nordtal.season.steward.alert;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.AlertBook;
import eu.nordtal.season.database.alert.AlertChannel;
import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.database.alert.DiscordRole;
import eu.nordtal.season.database.alert.RaisedAlert;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.Schedule;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.steward.push.PushSender;
import eu.nordtal.season.steward.push.PushSubscriptions;
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
    private final Messages texts;

    /**
     * @param sender null without a VAPID keypair, which leaves the admin channel as the only way out
     * @param publicUrl Steward's own address, which a post in the admin channel links to
     * @param texts the admin texts with their overrides, which a push is rendered from, since a lock screen renders
     *     nothing itself
     */
    public AlertRouter(
            final AlertBook book,
            final AlertPreferences preferences,
            final Supplier<List<DiscordId>> admins,
            final Inbox<BotRequest> bot,
            final @Nullable PushSubscriptions subscriptions,
            final @Nullable PushSender sender,
            final String publicUrl,
            final Messages texts) {
        this.book = Objects.requireNonNull(book, "book");
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.admins = Objects.requireNonNull(admins, "admins");
        this.bot = Objects.requireNonNull(bot, "bot");
        this.subscriptions = subscriptions;
        this.sender = sender;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        this.texts = Objects.requireNonNull(texts, "texts");
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
        try {
            bot.submit(
                    new BotRequest.PostAlert(
                            alert.level(),
                            alert.title(),
                            alert.detail(),
                            publicUrl + alert.path(),
                            alert.level() == Alert.Level.OK ? List.of() : wanting),
                    Actor.STEWARD,
                    Schedule.within(BOT_PATIENCE));
        } catch (final RuntimeException failure) {
            log.warn(
                    "Could not ask the bot to post the alert of {} on {}",
                    alert.type().key(),
                    alert.subject(),
                    failure);
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

    /** The push body {@code sw.js} reads: the title and the level in words, rendered here as plain text. */
    String payloadOf(final Alert alert) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", alert.type().key());
        body.put("level", alert.level().key());
        body.put("title", texts.format(Locales.DEFAULT, alert.title()));
        body.put("body", texts.format(Locales.DEFAULT, TEXTS.alert().level(alert.level())));
        body.put("path", alert.path());
        return Json.encode(body);
    }

    /** A real-looking alert of that type, to show what one looks like. */
    static Alert sample(final AlertType type) {
        return switch (type) {
            case SERVICE ->
                new Alert(type, Alert.Level.DOWN, "smp", TEXTS.alert().notRunning("smp"), "/services/smp");
            case BACKUP ->
                new Alert(type, Alert.Level.DOWN, "database dump", TEXTS.alert().noDump(), "/operations/backups");
            case DISK -> new Alert(type, Alert.Level.WARN, "disk", TEXTS.alert().disk(91), "/");
            case MEMORY ->
                new Alert(type, Alert.Level.WARN, "memory", TEXTS.alert().memory(93), "/");
            case DRIFT ->
                new Alert(type, Alert.Level.WARN, "registry", TEXTS.alert().notCompared(), "/operations/updates");
            case RUN ->
                new Alert(
                        type,
                        Alert.Level.DOWN,
                        "update",
                        TEXTS.alert().runFailed(UpdateKind.UPDATE),
                        "/operations/updates");
            case PAYMENT ->
                new Alert(type, Alert.Level.DOWN, "payment", TEXTS.alert().payment(), "/payments");
            case BOT ->
                new Alert(
                        type,
                        Alert.Level.WARN,
                        "access role",
                        TEXTS.alert().roleNotChanged(DiscordRole.ACCESS, true),
                        "/access");
        };
    }
}
