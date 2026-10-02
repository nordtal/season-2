package eu.nordtal.s2.steward.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.DatabaseRole;
import eu.nordtal.s2.database.TestDatabase;
import eu.nordtal.s2.database.alert.Alert;
import eu.nordtal.s2.database.alert.AlertBook;
import eu.nordtal.s2.database.alert.AlertChannel;
import eu.nordtal.s2.database.alert.AlertType;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.database.inbox.Inbox;
import eu.nordtal.s2.database.inbox.Request;
import eu.nordtal.s2.steward.push.PushSender;
import eu.nordtal.s2.steward.push.PushSubscriptions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Where a raised alert goes: to the browsers and the admin channel of every admin who wants it, once. */
class AlertRouterTest {

    private static final DiscordId FIRST = DiscordId.of("42");
    private static final DiscordId SECOND = DiscordId.of("43");

    private static DataSource dataSource;

    /** The owner, for fixtures and for whatever stands in for another service. */
    private static DataSource owner;

    private AlertBook book;
    private AlertPreferences preferences;
    private PushSubscriptions subscriptions;
    private Inbox<BotRequest> bot;
    private FakeSender sender;
    private AlertRouter router;

    @BeforeAll
    static void startDatabase() {
        final TestDatabase database = TestDatabase.fresh();
        owner = database.dataSource();
        // The role steward logs in as, so a statement it was never granted fails here first.
        dataSource = database.dataSourceAs(DatabaseRole.STEWARD);
    }

    @BeforeEach
    void freshTables() throws java.sql.SQLException {
        try (var connection = owner.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("TRUNCATE admin_alert, steward_push_subscription, steward_alert_preference, bot_inbox");
        }
        book = AlertBook.using(dataSource);
        preferences = new AlertPreferences(dataSource);
        subscriptions = new PushSubscriptions(dataSource);
        subscriptions.subscribe(FIRST, "https://push.example/a", "p-a", "a-a");
        subscriptions.subscribe(SECOND, "https://push.example/b", "p-b", "a-b");
        bot = Inbox.over(dataSource, BotRequest.TABLE);
        sender = new FakeSender();
        router = new AlertRouter(
                book,
                preferences,
                () -> List.of(FIRST, SECOND),
                bot,
                subscriptions,
                sender,
                "https://steward.example/");
    }

    private static Alert alert(final AlertType type, final Alert.Level level) {
        return new Alert(type, level, "smp", "smp is not running", "", "/services/smp");
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
        book.raise(alert(AlertType.SERVICE, Alert.Level.DOWN), "steward");
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
    }

    @Test
    void aSwitchedOffTypeAndADefaultOffTypeReachNoBrowser() {
        preferences.set(FIRST, AlertType.SERVICE, AlertChannel.PUSH, false);
        book.raise(alert(AlertType.SERVICE, Alert.Level.DOWN), "steward");
        book.raise(alert(AlertType.DRIFT, Alert.Level.WARN), "steward");
        router.route();

        assertEquals(
                List.of("https://push.example/b"),
                sender.sent.stream().map(FakeSender.Call::endpoint).toList());
    }

    @Test
    void anExpiredBrowserIsForgotten() {
        sender.expired.add("https://push.example/a");
        book.raise(alert(AlertType.BACKUP, Alert.Level.DOWN), "steward");
        router.route();

        assertEquals(
                List.of("https://push.example/b"),
                subscriptions.all().stream()
                        .map(PushSubscriptions.Subscription::endpoint)
                        .toList());
    }

    @Test
    void theAdminChannelGetsWhatItsAdminsWantMentioningOnlyThem() {
        // A service alert is not in Discord by default; a run is.
        book.raise(alert(AlertType.SERVICE, Alert.Level.DOWN), "steward");
        book.raise(alert(AlertType.RUN, Alert.Level.DOWN), "steward");
        preferences.set(SECOND, AlertType.RUN, AlertChannel.DISCORD, false);
        router.route();

        final List<BotRequest.PostAlert> posts = posted();
        assertEquals(1, posts.size());
        assertEquals(List.of(FIRST), posts.getFirst().mentions());
        assertEquals("https://steward.example/services/smp", posts.getFirst().detail());

        preferences.set(SECOND, AlertType.SERVICE, AlertChannel.DISCORD, true);
        book.raise(alert(AlertType.SERVICE, Alert.Level.DOWN), "steward");
        router.route();
        assertEquals(List.of(SECOND), posted().getFirst().mentions());
    }

    @Test
    void anAllClearIsPostedWithoutAMention() {
        book.raise(alert(AlertType.RUN, Alert.Level.OK), "steward");
        router.route();

        final List<BotRequest.PostAlert> posts = posted();
        assertEquals(1, posts.size());
        assertEquals(List.of(), posts.getFirst().mentions());
    }

    @Test
    void nobodyWantingItMeansNoPost() {
        preferences.set(FIRST, AlertType.PAYMENT, AlertChannel.DISCORD, false);
        preferences.set(SECOND, AlertType.PAYMENT, AlertChannel.DISCORD, false);
        book.raise(alert(AlertType.PAYMENT, Alert.Level.DOWN), "discord-bot");
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
