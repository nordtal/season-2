package eu.nordtal.season.discordbot;

import static eu.nordtal.season.database.AdminTexts.TEXTS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.AlertBook;
import eu.nordtal.season.database.alert.AlertType;
import eu.nordtal.season.database.alert.RaisedAlert;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.inbox.BotRequest;
import eu.nordtal.season.database.payment.PaymentMatch;
import eu.nordtal.season.discordbot.access.SeasonStart;
import eu.nordtal.season.discordbot.access.discord.LinkFlow;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.value.Mention;
import eu.nordtal.season.messages.value.Money;
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
        assertEquals(Mark.FAILED, AdminLog.mark(Alert.Level.DOWN));
        assertEquals(Mark.WARNING, AdminLog.mark(Alert.Level.WARN));
        assertEquals(Mark.DONE, AdminLog.mark(Alert.Level.OK));
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
                        new JournalDetail.AccessGranted(30, Instant.parse("2026-11-02T10:00:00Z"))));

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
                        new JournalDetail.KeyRegistered("*bold*")));

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
    void aBookedPaymentIsDrawnFromTheLineTheBookingWroteWithItsReferenceEscaped() {
        final MessageEmbed card = AdminLog.card(
                ADMIN,
                AuditLine.about(
                        JournalAction.SETTLE,
                        Actor.STEWARD,
                        DiscordId.of("400000000000000001"),
                        new JournalDetail.Settled(
                                UUID.randomUUID(),
                                "NT_7Q",
                                PaymentMatch.TAB,
                                30,
                                30,
                                Money.euroCents(500),
                                Money.euroCents(0))));

        assertEquals("💶 Payment settled", card.getTitle());
        assertTrue(card.getDescription().contains("NT\\_7Q"), card.getDescription());
    }

    @Test
    void aGrantWithoutASeasonStartIsALineTheBotWritesAboutThePerson() {
        final AuditLine line =
                SeasonStart.unanchored(DiscordId.of("400000000000000001"), Instant.parse("2026-11-02T10:00:00Z"));
        final MessageEmbed card = AdminLog.card(ADMIN, line);

        assertEquals(JournalAction.UNANCHORED_GRANT, line.action());
        assertEquals("⚠️ No season start", card.getTitle());
        assertEquals(
                "Access runs from <t:1793613600:f> instead of the SMP opening. Set the date in Steward under Season.",
                card.getDescription());
        assertEquals(List.of("By: the bot", "Concerns: <@400000000000000001>"), fields(card));
    }

    @Test
    void aMemberOutOfLinkCodesIsALineTheBotWritesAboutThem() {
        final AuditLine line = LinkFlow.lockedOut(DiscordId.of("400000000000000001"));
        final MessageEmbed card = AdminLog.card(ADMIN, line);

        assertEquals(JournalAction.LINK_CODES_EXHAUSTED, line.action());
        assertEquals("🔒 Too many wrong codes", card.getTitle());
        assertEquals("Refused linking for the rest of the hour.", card.getDescription());
        assertEquals(List.of("By: the bot", "Concerns: <@400000000000000001>"), fields(card));
    }

    private static List<String> fields(final MessageEmbed card) {
        return card.getFields().stream()
                .map(field -> field.getName() + ": " + field.getValue())
                .toList();
    }
}
