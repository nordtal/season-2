package eu.nordtal.s2.discordbot;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.alert.Alert;
import eu.nordtal.s2.database.alert.AlertBook;
import eu.nordtal.s2.database.alert.AlertType;
import eu.nordtal.s2.database.alert.RaisedAlert;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The bot raises an alert as a row for steward to route, and draws one steward routed back with its mentions. */
class AdminLogTest {

    private static final Alert DM = new Alert(
            AlertType.BOT, Alert.Level.WARN, "direct message", "A direct message was not delivered", "", "/access");

    /** A book that keeps what is raised, or refuses every raise as a database that is gone would. */
    private static final class Book implements AlertBook {

        final List<Alert> raised = new ArrayList<>();
        final List<String> by = new ArrayList<>();
        boolean gone;

        @Override
        public void raise(final Alert alert, final String raisedBy) {
            if (gone) {
                throw new IllegalStateException("the database is gone");
            }
            raised.add(alert);
            by.add(raisedBy);
        }

        @Override
        public boolean raiseOnce(final String source, final Alert alert, final String raisedBy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RaisedAlert> claimUnrouted() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RaisedAlert> recent(final int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int purge(final Duration age) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void anAlertIsARowRaisedByTheBot() {
        final Book book = new Book();

        AdminLog.raise(book, DM);

        assertEquals(List.of(DM), book.raised);
        assertEquals(List.of("discord-bot"), book.by);
    }

    @Test
    void aDatabaseThatIsGoneLosesTheAlertToTheLogNotTheCaller() {
        final Book book = new Book();
        book.gone = true;

        assertDoesNotThrow(() -> AdminLog.raise(book, DM));
        assertEquals(List.of(), book.raised);
    }

    @Test
    void everyNamedAdminIsMentionedOutsideTheEmbed() {
        assertEquals(
                "<@400000000000000001> <@400000000000000002>",
                AdminLog.mentions(List.of(DiscordId.of("400000000000000001"), DiscordId.of("400000000000000002"))));
    }

    @Test
    void nobodyNamedMeansNoMention() {
        assertNull(AdminLog.mentions(List.of()));
    }

    @Test
    void theLevelIsTheEmojiTheRestOfTheChannelUses() {
        assertEquals("🛑", AdminLog.emoji(Alert.Level.DOWN));
        assertEquals("⚠️", AdminLog.emoji(Alert.Level.WARN));
        assertEquals("✅", AdminLog.emoji(Alert.Level.OK));
    }
}
