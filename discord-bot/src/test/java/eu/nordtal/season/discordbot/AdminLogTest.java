package eu.nordtal.season.discordbot;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.id.Actor;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.database.JournalDetail;
import eu.nordtal.season.database.alert.Alert;
import eu.nordtal.season.database.alert.AlertBook;
import eu.nordtal.season.database.alert.Measure;
import eu.nordtal.season.database.alert.RaisedAlert;
import eu.nordtal.season.database.audit.AuditLine;
import eu.nordtal.season.database.audit.JournalAction;
import eu.nordtal.season.database.payment.PaymentMatch;
import eu.nordtal.season.discordbot.access.SeasonStart;
import eu.nordtal.season.discordbot.access.discord.LinkFlow;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.messages.value.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The bot raises alerts as rows, draws the alerts that steward routes back and posts journal lines as cards. */
class AdminLogTest {

    private static final DocumentRenderer ADMIN = StoredDocumentsStandIn.renderer();

    private static final Alert DM = new Alert(
            JournalAction.DM_NOT_DELIVERED,
            Alert.Level.WARN,
            new JournalDetail.MemberFailed(DiscordId.of("400000000000000001"), null));

    private static final class Book implements AlertBook {

        final List<Alert> raised = new ArrayList<>();
        final List<Actor> by = new ArrayList<>();
        boolean gone;

        @Override
        public UUID raise(final Alert alert, final Actor actor) {
            if (gone) {
                throw new IllegalStateException("the database is gone");
            }
            raised.add(alert);
            by.add(actor);
            return UUID.randomUUID();
        }

        @Override
        public boolean raiseOnce(final Alert alert, final Actor actor) {
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
        public Optional<RaisedAlert> alert(final UUID id) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void anAlertIsARowRaisedByTheBot() {
        final Book book = new Book();

        AdminLog.raise(book, DM);

        assertEquals(List.of(DM), book.raised);
        assertEquals(List.of(Actor.BOT), book.by);
    }

    @Test
    void aDatabaseThatIsGoneLosesTheAlertToTheLogNotTheCaller() {
        final Book book = new Book();
        book.gone = true;

        assertDoesNotThrow(() -> AdminLog.raise(book, DM));
        assertEquals(List.of(), book.raised);
    }

    @Test
    void anAlertPingsTheAdminsItNamesAndNobodyElse() {
        final MessageCreateData sent = AdminLog.pinging(
                card(List.of(DiscordId.of("400000000000000001")), LINK), List.of(DiscordId.of("400000000000000001")));

        assertTrue(sent.getAllowedMentions().isEmpty(), "no role, everyone or here ping: " + sent.getAllowedMentions());
        assertEquals(java.util.Set.of("400000000000000001"), sent.getMentionedUsers());
    }

    @Test
    void aJournalCardNamesPeopleWithoutPingingThem() {
        final MessageCreateData sent = AdminLog.pinging(
                AdminLog.card(ADMIN, LinkFlow.lockedOut(DiscordId.of("400000000000000001"))), List.of());

        assertTrue(
                sent.getAllowedMentions().isEmpty(), sent.getAllowedMentions().toString());
        assertEquals(java.util.Set.of(), sent.getMentionedUsers());
    }

    @Test
    void theLevelIsTheEmojiTheRestOfTheChannelUses() {
        assertEquals(Mark.FAILED, AdminLog.mark(Alert.Level.DOWN));
        assertEquals(Mark.WARNING, AdminLog.mark(Alert.Level.WARN));
        assertEquals(Mark.DONE, AdminLog.mark(Alert.Level.OK));
    }

    @Test
    void aJournalLineIsItsActionTheLineAndWhoWasInvolved() {
        final DocumentRenderer.Drawn card = AdminLog.card(
                ADMIN,
                new AuditLine(
                        JournalAction.GRANT_ACCESS,
                        Actor.STEWARD,
                        DiscordId.of("400000000000000002"),
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        new JournalDetail.AccessGranted(30, Instant.parse("2026-11-02T10:00:00Z"))));

        assertEquals("""
                ## 🎟️ Access granted
                Granted 30 days of access until <t:1793613600:D>.
                **By** Steward
                **Concerns** <@400000000000000002>
                **Minecraft** `00000000-0000-0000-0000-000000000001`""", card.text());
    }

    @Test
    void aValueInAJournalLineIsEscapedNeverMarkdown() {
        final DocumentRenderer.Drawn card = AdminLog.card(
                ADMIN,
                AuditLine.of(
                        JournalAction.REGISTER_KEY,
                        Actor.person(DiscordId.of("400000000000000001")),
                        new JournalDetail.KeyRegistered("*bold*")));

        assertEquals("""
                ## 📝 Security key added
                Added the security key "\\*bold\\*".
                **By** <@400000000000000001>""", card.text());
    }

    @Test
    void aRoutedAlertIsItsMentionsMarkTitleLinesAndLinkWithEveryValueEscaped() {
        final DocumentRenderer.Drawn card = card(List.of(DiscordId.of("400000000000000002")), LINK);

        assertEquals("""
                <@400000000000000002>
                ## 🛑 smp\\_1 is not running
                smp\\_1: Docker reports the state \\*gone\\*.""", card.text());
        final Button open =
                MessageComponentTree.of(card.components()).findAll(Button.class).getFirst();
        assertEquals("https://steward.example/services/smp_1", open.getUrl());
    }

    @Test
    void anAlertWithoutAPageCarriesNoButton() {
        assertEquals(
                List.of(),
                MessageComponentTree.of(card(List.of(), null).components()).findAll(Button.class));
    }

    @Test
    void aBookedPaymentIsDrawnFromTheLineTheBookingWroteWithItsReferenceEscaped() {
        final DocumentRenderer.Drawn card = AdminLog.card(
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

        assertTrue(card.text().startsWith("## 💶 Payment settled\n"), card.text());
        assertTrue(card.text().contains("NT\\_7Q"), card.text());
    }

    @Test
    void aGrantWithoutASeasonStartIsALineTheBotWritesAboutThePerson() {
        final AuditLine line =
                SeasonStart.unanchored(DiscordId.of("400000000000000001"), Instant.parse("2026-11-02T10:00:00Z"));

        assertEquals(JournalAction.UNANCHORED_GRANT, line.action());
        assertEquals("""
                ## ⚠️ No season start
                Access starts at <t:1793613600:f>, not at the SMP opening. Set the date in Steward under Season.
                **By** the bot
                **Concerns** <@400000000000000001>""", AdminLog.card(ADMIN, line).text());
    }

    @Test
    void aMemberOutOfLinkCodesIsALineTheBotWritesAboutThem() {
        final AuditLine line = LinkFlow.lockedOut(DiscordId.of("400000000000000001"));

        assertEquals(JournalAction.LINK_CODES_EXHAUSTED, line.action());
        assertEquals("""
                ## 🔒 Too many wrong codes
                Refused linking for the rest of the hour.
                **By** the bot
                **Concerns** <@400000000000000001>""", AdminLog.card(ADMIN, line).text());
    }

    private static final String LINK = "https://steward.example/services/smp_1";

    /** Draws a stopped service whose name and state need escaping. */
    private static DocumentRenderer.Drawn card(final List<DiscordId> admins, final @Nullable String link) {
        return AdminLog.card(
                ADMIN,
                new Alert(
                        JournalAction.SERVICE_DOWN,
                        Alert.Level.DOWN,
                        List.of(Measure.state(Measure.Gauge.CONTAINER_STATE, "smp_1", "*gone*"))),
                admins,
                link);
    }
}
