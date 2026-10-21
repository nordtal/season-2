package eu.nordtal.season.steward.alert;

import static eu.nordtal.season.database.AdminTexts.TEXTS;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.common.json.Json;
import eu.nordtal.season.common.language.Locales;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.AlertBook;
import eu.nordtal.season.database.alert.AlertChannel;
import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.database.alert.DiscordRole;
import eu.nordtal.season.database.alert.Measure;
import eu.nordtal.season.database.alert.Measure.Gauge;
import eu.nordtal.season.database.alert.RaisedAlert;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.Schedule;
import eu.nordtal.season.database.payment.TabRefusal;
import eu.nordtal.season.database.update.RunStep;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.value.Money;
import eu.nordtal.season.steward.push.PushSender;
import eu.nordtal.season.steward.push.PushSubscriptions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends each alert to the browsers and to the admin channel of every admin who wants it.
 * A row is marked routed before it is sent, so a channel gets an alert at most once.
 */
public final class AlertRouter {

    static final Duration BOT_PATIENCE = Duration.ofHours(1);

    private static final DiscordId SAMPLE_MEMBER = DiscordId.of("100000000000000001");

    private static final Logger log = LoggerFactory.getLogger(AlertRouter.class);

    private final AlertBook book;
    private final AlertPreferences preferences;
    private final Supplier<List<DiscordId>> admins;
    private final Inbox<BotRequest> bot;
    private final @Nullable PushSubscriptions subscriptions;
    private final @Nullable PushSender sender;
    private final Messages texts;

    /** @param sender null without a VAPID keypair, so the admin channel is the only channel */
    public AlertRouter(
            final AlertBook book,
            final AlertPreferences preferences,
            final Supplier<List<DiscordId>> admins,
            final Inbox<BotRequest> bot,
            final @Nullable PushSubscriptions subscriptions,
            final @Nullable PushSender sender,
            final Messages texts) {
        this.book = Objects.requireNonNull(book, "book");
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.admins = Objects.requireNonNull(admins, "admins");
        this.bot = Objects.requireNonNull(bot, "bot");
        this.subscriptions = subscriptions;
        this.sender = sender;
        this.texts = Objects.requireNonNull(texts, "texts");
    }

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
            post(one, chosen, everyAdmin);
        }
    }

    /** Maps {@link PushSender.Result} for callers outside the push package. */
    public enum Delivery {
        SENT,
        /** The push service reports the subscription as gone. The row has been removed. */
        GONE,
        FAILED
    }

    /** Sends a sample of {@code type} to one browser. Returns null without a VAPID keypair. */
    public @Nullable Delivery sendSample(final PushSubscriptions.Subscription subscription, final AlertType type) {
        if (sender == null || subscriptions == null) {
            return null;
        }
        final PushSender.Result result = send(subscription, payloadOf(sample(type.action())));
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
                // A 404 or 410 means the subscription is dead, so the row is removed.
                case EXPIRED -> Objects.requireNonNull(subscriptions).expired(subscription.endpoint());
                case FAILED -> log.warn("push to {} failed (not a 404/410)", subscription.endpoint());
            }
        }
    }

    /** Asks the bot to post the alert for the admins who want it there. An OK alert mentions nobody. */
    private void post(
            final RaisedAlert raised, final AlertPreferences.Chosen chosen, final List<DiscordId> everyAdmin) {
        final Alert alert = raised.alert();
        final List<DiscordId> wanting = everyAdmin.stream()
                .filter(admin -> chosen.wants(admin, alert.type(), AlertChannel.DISCORD))
                .toList();
        if (wanting.isEmpty()) {
            return;
        }
        try {
            bot.submit(
                    new BotRequest.PostAlert(raised.id(), alert.level() == Alert.Level.OK ? List.of() : wanting),
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

    /** Builds the push body that {@code sw.js} reads. */
    String payloadOf(final Alert alert) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", alert.type().key());
        body.put("level", alert.level().key());
        body.put("title", texts.format(Locales.DEFAULT, alert.title()));
        body.put("body", texts.format(Locales.DEFAULT, TEXTS.alert().level(alert.level())));
        body.put("path", alert.path());
        return Json.encode(body);
    }

    /** Returns a sample of an alert's action, with every value it can show. */
    public static Alert sample(final JournalAction action) {
        return switch (action) {
            case SERVICE_DOWN ->
                measured(action, Alert.Level.DOWN, Measure.state(Gauge.CONTAINER_STATE, "smp", "exited"));
            case BACKUP_STALE ->
                measured(action, Alert.Level.DOWN, new Measure(Gauge.BACKUP_AGE_HOURS, Alert.DUMP, null, 26.0, null));
            case DISK_FULL -> measured(action, Alert.Level.WARN, Measure.against(Gauge.DISK_PERCENT, "disk", 91, 85));
            case MEMORY_FULL ->
                measured(action, Alert.Level.WARN, Measure.against(Gauge.MEMORY_PERCENT, "memory", 93, 90));
            case IMAGE_DRIFT -> measured(action, Alert.Level.WARN, Measure.state(Gauge.OLDER_IMAGE, "smp", null));
            case PACK_DEAD ->
                measured(
                        action,
                        Alert.Level.DOWN,
                        Measure.state(Gauge.PACK, StackAlerts.PACK, "https://nordtal.eu/pack.zip"));
            case RUN_FAILED ->
                event(
                        action,
                        new JournalDetail.RunFailed(
                                42L,
                                UpdateKind.UPDATE,
                                List.of(new JournalDetail.FailedStep(
                                        RunStep.Kind.START,
                                        "smp",
                                        RunStep.Outcome.FAILED,
                                        RunStep.Reason.NOT_HEALTHY))));
            case PAYMENT_NEEDS_LOOK ->
                event(
                        action,
                        new JournalDetail.PaymentFlagged(
                                JournalDetail.PaymentFlagged.Problem.UNKNOWN_REFERENCE,
                                42,
                                Money.euroCents(500),
                                "NT-ABC123",
                                null,
                                null));
            case LINK_REFUSED ->
                event(action, new JournalDetail.LinkRefused(new UUID(0, 42), "NT-ABC123", TabRefusal.UNREACHABLE));
            case LINK_FAILED, PURCHASE_FAILED ->
                event(action, new JournalDetail.MemberFailed(SAMPLE_MEMBER, "Unknown Member"));
            case DM_NOT_DELIVERED -> event(action, new JournalDetail.MemberFailed(SAMPLE_MEMBER, null));
            case ROLE_NOT_CHANGED ->
                event(
                        action,
                        new JournalDetail.RoleNotChanged(
                                DiscordRole.ACCESS, true, SAMPLE_MEMBER, "Missing Permissions"));
            case ROLE_AMBIGUOUS -> event(action, new JournalDetail.RoleNamed("Access", null));
            case ROLE_NOT_CREATED -> event(action, new JournalDetail.RoleNamed("Access", "Missing Permissions"));
            case LOCK_NOT_KEPT ->
                event(
                        action,
                        new JournalDetail.ChannelsNotClosed(
                                List.of(new JournalDetail.ChannelFailure("general", "Missing Access"))));
            case LOCK_WITHOUT_CHANNEL -> new Alert(action, Alert.Level.WARN, List.of());
            default -> throw new IllegalArgumentException(action + " is no alert");
        };
    }

    private static Alert measured(final JournalAction action, final Alert.Level level, final Measure measure) {
        return new Alert(action, level, List.of(measure));
    }

    private static Alert event(final JournalAction action, final JournalDetail detail) {
        return new Alert(action, action == JournalAction.RUN_FAILED ? Alert.Level.DOWN : Alert.Level.WARN, detail);
    }
}
