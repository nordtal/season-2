package eu.nordtal.season.steward.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(payload.contains("\"body\":\"down\""), payload);
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
    void theAdminChannelGetsWhatItsAdminsWantMentioningOnlyThem() {
        // By default a service alert is not sent to Discord, and a run alert is.
        book.raise(alert(AlertType.SERVICE, Alert.Level.DOWN), Actor.STEWARD);
        final UUID failed = book.raise(alert(AlertType.RUN, Alert.Level.DOWN), Actor.STEWARD);
        preferences.set(SECOND, AlertType.RUN, AlertChannel.DISCORD, false);
        router.route();

        assertEquals(
                List.of(new BotRequest.PostAlert(failed, List.of(FIRST))),
                posted(),
                "the bot is told which entry to draw and whom to mention");

        preferences.set(SECOND, AlertType.SERVICE, AlertChannel.DISCORD, true);
        book.raise(alert(AlertType.SERVICE, Alert.Level.DOWN), Actor.STEWARD);
        router.route();
        assertEquals(List.of(SECOND), posted().getFirst().mentions());
    }

    @Test
    void anAllClearIsPostedWithoutAMention() {
        book.raise(alert(AlertType.RUN, Alert.Level.OK), Actor.STEWARD);
        router.route();

        final List<BotRequest.PostAlert> posts = posted();
        assertEquals(1, posts.size());
        assertEquals(List.of(), posts.getFirst().mentions());
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
