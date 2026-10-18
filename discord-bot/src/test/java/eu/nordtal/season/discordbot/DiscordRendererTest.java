package eu.nordtal.season.discordbot;

import static eu.nordtal.season.database.DatabaseMessages.MESSAGES;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.id.DiscordId;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.messages.context.DiscordMemberContext;
import eu.nordtal.season.messages.context.MilestoneContext;
import eu.nordtal.season.messages.context.TeamContext;
import eu.nordtal.season.messages.value.Money;
import java.time.Instant;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class DiscordRendererTest {

    private static final DocumentRenderer DOCUMENTS = StoredDocumentsStandIn.renderer();
    private static final Instant AT = Instant.parse("2026-10-03T18:40:00Z");

    @Test
    void aTeamNameWithMarkdownReachesDiscordAsWritten() {
        assertEquals(
                "You joined team **Red\\_Fox\\*\\***.",
                text(Locale.ENGLISH, Replies.INVITE_ACCEPTED, new Replies.Team(new TeamContext("Red_Fox**"))));
    }

    @Test
    void aTextBeingTriedIsWrittenAsTheKeysTextsAreWithItsValuesEscaped() {
        assertEquals(
                "**Red\\_Fox\\*\\*** ist geschafft.",
                DOCUMENTS
                        .messages()
                        .format(
                                Locale.GERMAN,
                                MESSAGES.announcement().milestone(new MilestoneContext("Red_Fox**")),
                                "**{milestone.name}** ist geschafft."));
    }

    @Test
    void aMomentIsDiscordsTimestampInEveryReadersZone() {
        assertEquals(
                "Your access lasts until **<t:1791052800:f>**. Have fun on Nordtal.",
                text(Locale.ENGLISH, Replies.DM_GRANTED, new Replies.Until(AT)));
    }

    @Test
    void aMemberIsAMentionAndMoneyIsWrittenAsTheReadersLanguageWritesIt() {
        final Replies.Donated donated =
                new Replies.Donated(new DiscordMemberContext(DiscordId.of("123"), "Al_ex"), Money.euroCents(300));

        assertEquals("<@123> donated €3.00. Thank you!", text(Locale.ENGLISH, Replies.PUBLIC_DONATION, donated));
        assertEquals("<@123> hat 3,00 € gespendet. Danke!", text(Locale.GERMAN, Replies.PUBLIC_DONATION, donated));
    }

    private static <V extends Record> String text(
            final Locale locale, final eu.nordtal.season.messages.document.DocumentSpec<V> document, final V values) {
        return DOCUMENTS
                .reply(locale, new DocumentRenderer.Reply<>(document, values))
                .text();
    }
}
