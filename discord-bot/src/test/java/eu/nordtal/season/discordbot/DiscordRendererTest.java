package eu.nordtal.season.discordbot;

import static eu.nordtal.season.discordbot.AccessMessages.MESSAGES;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.context.DiscordMemberContext;
import eu.nordtal.season.messages.value.Money;
import java.time.Instant;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** The Discord target: what a member reads is what was written, and times and members are Discord's own. */
class DiscordRendererTest {

    private static final DiscordRenderer DISCORD =
            DiscordRenderer.of(Messages.load("messages/access", Locale.ENGLISH, Locale.GERMAN));
    private static final Instant AT = Instant.parse("2026-10-03T18:40:00Z");

    @Test
    void aTeamNameWithMarkdownReachesDiscordAsWritten() {
        assertEquals(
                "Team **Red\\_Fox\\*\\*** is registered. You can invite one partner below, or just show up.",
                DISCORD.format(Locale.ENGLISH, MESSAGES.register().success("Red_Fox**")));
    }

    @Test
    void aTextBeingTriedIsWrittenAsTheKeysTextsAreWithItsValuesEscaped() {
        assertEquals(
                "**Red\\_Fox\\*\\*** ist dabei.",
                DISCORD.format(Locale.GERMAN, MESSAGES.register().success("Red_Fox**"), "**{name}** ist dabei."));
    }

    @Test
    void aMomentIsDiscordsTimestampInEveryReadersZone() {
        assertEquals(
                "Your access is active until **<t:1791052800:f>**. Have fun on nordtal.",
                DISCORD.format(Locale.ENGLISH, MESSAGES.dm().granted(AT)));
        assertEquals(
                "The link expires <t:1791052800:R>.",
                DISCORD.format(Locale.ENGLISH, MESSAGES.purchase().linkSection().ttl(AT)));
    }

    @Test
    void aMemberIsAMention() {
        assertEquals(
                "<@123> donated €3.00. Thank you!",
                DISCORD.format(
                        Locale.ENGLISH,
                        MESSAGES.publicSection()
                                .donation(
                                        new DiscordMemberContext(DiscordId.of("123"), "Al_ex"), Money.euroCents(300))));
    }

    @Test
    void moneyIsWrittenAsTheReadersLanguageWritesIt() {
        assertEquals(
                "Gesamt: **3,00\u00a0€**",
                DISCORD.format(
                        Locale.GERMAN, MESSAGES.purchase().summarySection().total(Money.euroCents(300))));
    }

    @Test
    void aLinkIsNeverEscaped() {
        assertEquals(
                "Pay **€3.00** here: https://bunq.me/t/a_b_c",
                DISCORD.format(
                        Locale.ENGLISH, MESSAGES.purchase().link(Money.euroCents(300), "https://bunq.me/t/a_b_c")));
    }

    @Test
    void aPlainTextKeepsItsValuesAsTheyAre() {
        assertEquals(
                "Add a €3.00 donation",
                DISCORD.format(
                        Locale.ENGLISH, MESSAGES.purchase().button().donation().add(Money.euroCents(300))));
        assertEquals(
                "1,200 players",
                DISCORD.format(Locale.ENGLISH, MESSAGES.status().smp(1200)));
    }

    @Test
    void aPhaseReadsAsItsName() {
        assertEquals(
                "The network is now in smp (it was in pre-event).",
                DISCORD.format(Locale.ENGLISH, MESSAGES.announce().phase(SeasonPhase.SMP, SeasonPhase.PRE_EVENT)));
    }
}
