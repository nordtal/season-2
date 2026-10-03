package eu.nordtal.s2.discordbot;

import static eu.nordtal.s2.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.nordtal.s2.common.id.Actor;
import eu.nordtal.s2.common.id.DiscordId;
import eu.nordtal.s2.database.alert.Alert;
import eu.nordtal.s2.database.alert.AlertBook;
import eu.nordtal.s2.database.alert.AlertType;
import eu.nordtal.s2.database.alert.RaisedAlert;
import eu.nordtal.s2.database.audit.AuditLine;
import eu.nordtal.s2.database.audit.JournalAction;
import eu.nordtal.s2.database.inbox.BotRequest;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.value.Mention;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.junit.jupiter.api.Test;

/**
 * The bot raises alerts as rows, draws the ones steward routes back, and posts its journal lines as cards.
 *
 * An alert is a row for steward to route and comes back with its mentions; a journal line is rendered by the admin
 * bundle.
 */
class AdminLogTest {

    private static final DiscordRenderer ADMIN = DiscordRenderer.of(
            Messages.load(AdminLogTest.class.getClassLoader(), List.of("messages/admin"), Locale.ENGLISH));

    private static final Alert DM = new Alert(
            AlertType.BOT, Alert.Level.WARN, "direct message", TEXTS.alert().dm(), "/access");

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

    @Test
    void aJournalLineIsItsActionTheLineAndWhoWasInvolved() {
        final MessageEmbed card = AdminLog.card(
                ADMIN,
                new AuditLine(
                        JournalAction.GRANT_ACCESS,
                        Actor.STEWARD,
                        DiscordId.of("400000000000000002"),
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        TEXTS.journal().grantAccess(30, Instant.parse("2026-11-02T10:00:00Z"))));

        assertEquals("🎟️ Access granted", card.getTitle());
        assertEquals("30 days of access, until <t:1793613600:D>.", card.getDescription());
        assertEquals(
                List.of(
                        "By: Steward",
                        "Concerns: <@400000000000000002>",
                        "Minecraft: `00000000-0000-0000-0000-000000000001`"),
                card.getFields().stream()
                        .map(field -> field.getName() + ": " + field.getValue())
                        .toList());
    }

    @Test
    void aValueInAJournalLineIsEscapedNeverMarkdown() {
        final MessageEmbed card = AdminLog.card(
                ADMIN,
                AuditLine.of(
                        JournalAction.REGISTER_KEY,
                        Actor.person(DiscordId.of("400000000000000001")),
                        TEXTS.journal().registerKey("*bold*")));

        assertEquals("📝 Security key added", card.getTitle());
        assertEquals("Added the security key \"\\*bold\\*\".", card.getDescription());
        assertEquals(
                "By: <@400000000000000001>",
                card.getFields().getFirst().getName() + ": "
                        + card.getFields().getFirst().getValue());
    }

    @Test
    void aRoutedAlertIsItsMarkTitleLinesAndLinkWithEveryValueEscaped() {
        final MessageEmbed card = AdminLog.card(
                ADMIN,
                new BotRequest.PostAlert(
                        Alert.Level.DOWN,
                        TEXTS.alert().notRunning("smp_1"),
                        List.of(TEXTS.alert().failedFor(Mention.of(DiscordId.of("400000000000000001")), "*gone*")),
                        "https://steward.example/services/smp_1",
                        List.of()));

        assertEquals("🛑 smp\\_1 is not running", card.getTitle());
        assertEquals(
                "<@400000000000000001>: \\*gone\\*\nhttps://steward.example/services/smp_1", card.getDescription());
    }

    @Test
    void aBookedPaymentIsNotedWithItsReferenceEscapedAndItsEndAsADiscordTimestamp() {
        final MessageEmbed card = AdminLog.card(
                ADMIN,
                "💶",
                TEXTS.note().paymentBooked(),
                TEXTS.note()
                        .booked(
                                "NT_7Q",
                                Mention.of(DiscordId.of("400000000000000001")),
                                30,
                                Instant.parse("2026-11-02T10:00:00Z")));

        assertEquals("💶 Payment booked", card.getTitle());
        assertEquals("NT\\_7Q for <@400000000000000001>: 30 days, until <t:1793613600:f>", card.getDescription());
    }
}
