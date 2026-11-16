package eu.nordtal.season.steward.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.AlertBook;
import eu.nordtal.season.database.alert.AlertChannel;
import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.database.alert.Measure;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.inbox.Inbox;
import eu.nordtal.season.database.inbox.Request;
import eu.nordtal.season.database.update.UpdateDirectory;
import eu.nordtal.season.database.update.UpdateKind;
import eu.nordtal.season.steward.AdminPlain;
import eu.nordtal.season.steward.push.PushSender;
import eu.nordtal.season.steward.push.PushSubscriptions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests that a raised alert goes once to the browsers and the admin channel of every admin who wants it. */
class AlertRouterTest {

    private static final DiscordId FIRST = DiscordId.of("42");
    private static final DiscordId SECOND = DiscordId.of("43");

    private static DataSource dataSource;

    /** The owner role, for fixtures and for other services. */
    private static DataSource owner;

    private AlertBook book;
    private AlertPreferences preferences;
    private PushSubscriptions subscriptions;
    private Inbox<BotRequest> bot;
    private FakeSender sender;
    private AlertRouter router;

    /** The run a run alert is about. */
    private long run;

    @BeforeAll
    static void startDatabase() {
        final TestDatabase database = TestDatabase.fresh();
        owner = database.dataSource();
        // Connects as the role that Steward uses, so a missing grant fails here.
        dataSource = database.dataSourceAs(DatabaseRole.STEWARD);
    }

    @BeforeEach
    void freshTables() throws java.sql.SQLException {
        try (var connection = owner.getConnection();
                var statement = connection.createStatement()) {
            statement.execute(
                    "TRUNCATE journal_entry, steward_inbox, steward_push_subscription, steward_alert_preference, bot_inbox"
                            + " CASCADE");
        }
        run = UpdateDirectory.using(dataSource)
                .submit(UpdateKind.UPDATE, Actor.STEWARD, null)
                .id();
        book = AlertBook.using(dataSource);
        preferences = new AlertPreferences(dataSource);
        subscriptions = new PushSubscriptions(dataSource);
        subscriptions.subscribe(FIRST, "https://push.example/a", "p-a", "a-a");
        subscriptions.subscribe(SECOND, "https://push.example/b", "p-b", "a-b");
        bot = Inbox.over(dataSource, BotRequest.TABLE);
        sender = new FakeSender();
        router = new AlertRouter(
                book, preferences, () -> List.of(FIRST, SECOND), bot, subscriptions, sender, AdminPlain.bundle());
    }

    /** Returns the sample of {@code type} at {@code level}, a run's about a run that exists. */
    private Alert alert(final AlertType type, final Alert.Level level) {
        if (type == AlertType.RUN) {
            return new Alert(
                    JournalAction.RUN_FAILED, level, new JournalDetail.RunFailed(run, UpdateKind.UPDATE, List.of()));
        }
        final Alert sample = AlertRouter.sample(type.action());
        return new Alert(sample.action(), level, sample.measured(), sample.detail());
    }

    private List<BotRequest.PostAlert> posted() {
        final List<BotRequest.PostAlert> posts = new ArrayList<>();
        final Inbox<BotRequest> theBot = Inbox.over(owner, BotRequest.TABLE);
        for (Optional<Request<BotRequest>> one = theBot.claim(); one.isPresent(); one = theBot.claim()) {
            posts.add((BotRequest.PostAlert) one.get().payload());
        }
        return posts;
    }

    @Test
    void anAlertReachesEveryBrowserOnceWithItsTitleAndLevel() {
        book.raise(alert(AlertType.SERVICE, Alert.Level.DOWN), Actor.STEWARD);
        router.route();
        router.route();

        assertEquals(
                Set.of("https://push.example/a", "https://push.example/b"),
                new HashSet<>(
                        sender.sent.stream().map(FakeSender.Call::endpoint).toList()));
        assertEquals(2, sender.sent.size(), "a routed row was sent again");
        final String payload = sender.sent.getFirst().payload();
        assertTrue(payload.contains("\"level\":\"down\""), payload);
        assertTrue(payload.contains("\"title\":\"smp is not running\""), payload);
        assertTrue(payload.contains("\"body\":\"smp: Docker reports the state exited.\""), payload);
    }

    /** A title leaves a long name out, so the push body names it, and two services give two pushes. */
    @Test
    void twoServicesOrVolumesGiveTwoDifferentPushes() {
        final String smp = router.payloadOf(older("smp"));
        final String proxy = router.payloadOf(older("proxy"));
        assertTrue(smp.contains("\"body\":\"smp runs an image older than the registry has.\""), smp);
        assertTrue(proxy.contains("\"body\":\"proxy runs an image older than the registry has.\""), proxy);
        assertNotEquals(smp, proxy);

        final String volume = router.payloadOf(new Alert(
                JournalAction.BACKUP_STALE,
                Alert.Level.DOWN,
                List.of(new Measure(Measure.Gauge.BACKUP_AGE_HOURS, "mc-hunger-games-plugins", null, 26.0, null))));
        assertTrue(volume.contains("\"title\":\"A volume has no archive yet\""), volume);
        assertTrue(volume.contains("\"body\":\"mc-hunger-games-plugins: there is none yet.\""), volume);
    }

    /** A merged alert names every subject in its push, and the push shows no Markdown. */
    @Test
    void twoExitedServicesAreBothNamedInOnePushWithoutMarkdown() {
        final String both = router.payloadOf(new Alert(
                JournalAction.SERVICE_DOWN,
                Alert.Level.DOWN,
                List.of(
                        Measure.state(Measure.Gauge.CONTAINER_STATE, "smp", "exited"),
                        Measure.state(Measure.Gauge.CONTAINER_STATE, "proxy", "exited"))));
        assertTrue(
                both.contains(
                        "\"body\":\"smp: Docker reports the state exited.\\nproxy: Docker reports the state exited.\""),
                both);

        final String pack = router.payloadOf(AlertRouter.sample(JournalAction.PACK_DEAD));
        assertTrue(pack.contains("\"body\":\"https://nordtal.eu/pack.zip does not answer."), pack);
        assertFalse(pack.contains("`"), pack);
    }

    private static Alert older(final String service) {
        return new Alert(
                JournalAction.IMAGE_DRIFT,
                Alert.Level.WARN,
                List.of(Measure.state(Measure.Gauge.OLDER_IMAGE, service, null)));
    }

    @Test
    void aSwitchedOffTypeAndADefaultOffTypeReachNoBrowser() {
        preferences.set(FIRST, AlertType.SERVICE, AlertChannel.PUSH, false);
        book.raise(alert(AlertType.SERVICE, Alert.Level.DOWN), Actor.STEWARD);
        book.raise(alert(AlertType.DRIFT, Alert.Level.WARN), Actor.STEWARD);
        router.route();

        assertEquals(
                List.of("https://push.example/b"),
                sender.sent.stream().map(FakeSender.Call::endpoint).toList());
    }

    @Test
    void anExpiredBrowserIsForgotten() {
        sender.expired.add("https://push.example/a");
        book.raise(alert(AlertType.BACKUP, Alert.Level.DOWN), Actor.STEWARD);
        router.route();

        assertEquals(
                List.of("https://push.example/b"),
                subscriptions.all().stream()
                        .map(PushSubscriptions.Subscription::endpoint)
                        .toList());
    }

    @Test
    void theAdminChannelGetsWhatAnAdminWantsMentioningTheAdminRole() {
        // By default a service alert is not sent to Discord, and a run alert is.
        book.raise(alert(AlertType.SERVICE, Alert.Level.DOWN), Actor.STEWARD);
        final UUID failed = book.raise(alert(AlertType.RUN, Alert.Level.DOWN), Actor.STEWARD);
        preferences.set(SECOND, AlertType.RUN, AlertChannel.DISCORD, false);
        router.route();

        assertEquals(
                List.of(new BotRequest.PostAlert(failed, true)),
                posted(),
                "the bot is told which entry to draw, and one admin wanting it is enough");
    }

    @Test
    void anAllClearIsPostedWithoutAMention() {
        book.raise(alert(AlertType.RUN, Alert.Level.OK), Actor.STEWARD);
        router.route();

        final List<BotRequest.PostAlert> posts = posted();
        assertEquals(1, posts.size());
        assertFalse(posts.getFirst().mention());
    }

    @Test
    void nobodyWantingItMeansNoPost() {
        preferences.set(FIRST, AlertType.PAYMENT, AlertChannel.DISCORD, false);
        preferences.set(SECOND, AlertType.PAYMENT, AlertChannel.DISCORD, false);
        book.raise(alert(AlertType.PAYMENT, Alert.Level.DOWN), Actor.BOT);
        router.route();

        assertEquals(List.of(), posted());
    }

    @Test
    void everyTypeHasASampleThatGoesOut() {
        final PushSubscriptions.Subscription first = subscriptions.of(FIRST).getFirst();
        for (final AlertType type : AlertType.values()) {
            assertEquals(AlertRouter.Delivery.SENT, router.sendSample(first, type), type.key());
        }
        assertEquals(AlertType.values().length, sender.sent.size());
    }

    private static final class FakeSender implements PushSender {
        record Call(String endpoint, String payload) {}

        private final List<Call> sent = new ArrayList<>();
        private final Set<String> expired = new HashSet<>();

        @Override
        public Result send(final PushSubscriptions.Subscription subscription, final String payload) {
            sent.add(new Call(subscription.endpoint(), payload));
            return expired.contains(subscription.endpoint()) ? Result.EXPIRED : Result.SENT;
        }
    }
}
