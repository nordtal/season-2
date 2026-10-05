package eu.nordtal.season.database.alert;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.DatabaseRole;
import eu.nordtal.season.database.TestDatabase;
import eu.nordtal.season.database.notify.Channel;
import eu.nordtal.season.database.notify.Notifications;
import eu.nordtal.season.database.notify.PostgresNotifications;
import eu.nordtal.season.messages.value.Mention;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link AlertBook} against the real migrations: the bot raises, steward claims each row once and in order. */
class AlertBookIntegrationTest {

    private static TestDatabase database;

    private static final Alert DM = new Alert(
            AlertType.BOT,
            Alert.Level.WARN,
            "DM",
            TEXTS.alert().dm(),
            List.of(
                    TEXTS.alert().to(Mention.of(DiscordId.of("100000000000000001"))),
                    TEXTS.alert().words("Hi")),
            "/access");

    private static final Alert RUN =
            new Alert(AlertType.SERVICE, Alert.Level.DOWN, "smp", TEXTS.alert().notRunning("smp"), "/");

    @BeforeAll
    static void startDatabase() {
        database = TestDatabase.fresh();
    }

    @BeforeEach
    void emptyBook() throws SQLException {
        execute("TRUNCATE TABLE admin_alert");
    }

    @Test
    void theBotRaisesAndStewardClaimsEveryRowOnceOldestFirst() {
        final AlertBook bot = AlertBook.using(database.dataSourceAs(DatabaseRole.DISCORD_BOT));
        final AlertBook steward = AlertBook.using(database.dataSourceAs(DatabaseRole.STEWARD));

        bot.raise(DM, "discord-bot");
        steward.raise(RUN, "steward");

        final List<RaisedAlert> claimed = steward.claimUnrouted();
        assertEquals(List.of(DM, RUN), claimed.stream().map(RaisedAlert::alert).toList());
        assertEquals("discord-bot", claimed.getFirst().raisedBy());
        assertTrue(steward.claimUnrouted().isEmpty(), "a routed row was handed out a second time");
    }

    @Test
    void oneSourceRaisesOneRowHoweverOftenItIsSeen() {
        final AlertBook steward = AlertBook.using(database.dataSourceAs(DatabaseRole.STEWARD));

        assertTrue(steward.raiseOnce("run:42", RUN, "steward"));
        assertFalse(steward.raiseOnce("run:42", RUN, "steward"));

        assertEquals(1, steward.recent(10).size());
    }

    @Test
    void aRaiseIsAnnouncedOnTheAlertChannel() throws Exception {
        final AlertBook bot = AlertBook.using(database.dataSourceAs(DatabaseRole.DISCORD_BOT));
        try (Notifications listening = PostgresNotifications.connector(
                        database.jdbcUrl(), database.username(), database.password(), 5, "alert-book-test")
                .listen(Set.of(Channel.ALERT))) {
            bot.raise(DM, "discord-bot");

            assertTrue(listening.awaitNotification(Duration.ofSeconds(10)), "nobody heard of the alert");
        }
    }

    @Test
    void onlyARoutedRowOlderThanTheAgeIsPurged() throws SQLException {
        final AlertBook steward = AlertBook.using(database.dataSource());
        steward.raise(RUN, "steward");
        steward.claimUnrouted();
        steward.raise(DM, "discord-bot");
        execute("UPDATE admin_alert SET raised = now() - interval '40 days'");

        assertEquals(1, steward.purge(Duration.ofDays(30)));
        assertEquals(
                List.of(DM), steward.recent(10).stream().map(RaisedAlert::alert).toList());
    }

    private static void execute(final String sql) throws SQLException {
        try (Connection connection = database.dataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
