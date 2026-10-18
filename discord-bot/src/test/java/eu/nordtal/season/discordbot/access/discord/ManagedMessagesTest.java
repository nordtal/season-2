package eu.nordtal.season.discordbot.access.discord;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.payment.Tier;
import eu.nordtal.season.database.payment.Tiers;
import eu.nordtal.season.discordbot.DocumentRenderer;
import eu.nordtal.season.discordbot.Ids;
import eu.nordtal.season.discordbot.StoredDocumentsStandIn;
import eu.nordtal.season.messages.document.DocumentCheck;
import eu.nordtal.season.messages.value.Money;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import org.junit.jupiter.api.Test;

class ManagedMessagesTest {

    private static final DocumentRenderer RENDERER = StoredDocumentsStandIn.renderer();

    @Test
    void theContributionMessageListsEachTierOverItsBannerWithTheBuyButtonUnderIt() {
        final DocumentRenderer.Drawn drawn = ManagedMessages.contribution(
                RENDERER, Tiers.of(List.of(new Tier(30, 500), new Tier(90, 1250)), 500), Locale.ENGLISH);
        final Container box = (Container) drawn.components().getFirst();
        final ActionRow row = (ActionRow) drawn.components().getLast();

        assertAll(
                () -> assertEquals(
                        "### Prices\n**€5.00** 30 days\n**€12.50** 90 days",
                        ((TextDisplay) box.getComponents().get(1)).getContent()),
                () -> assertEquals(List.of("contribution.png"), drawn.attachments()),
                () -> assertEquals(Ids.BUY, row.getButtons().getFirst().getCustomId()),
                () -> assertEquals("Buy access", row.getButtons().getFirst().getLabel()));
    }

    @Test
    void theLinkStepsNameTheButtonByItsOwnLabel() {
        final DocumentRenderer.Drawn drawn = ManagedMessages.link(RENDERER, Locale.GERMAN);
        final Container box = (Container) drawn.components().getFirst();
        final ActionRow row = (ActionRow) drawn.components().getLast();

        assertEquals(
                "### Schritte\nServer betreten → Code kopieren → **"
                        + row.getButtons().getFirst().getLabel() + "**",
                ((TextDisplay) box.getComponents().get(1)).getContent());
    }

    @Test
    void aDataListTooLongForOneMessageLosesItsLastItemsToACount() {
        final List<ManagedMessages.Tier> tiers = IntStream.range(1, 400)
                .mapToObj(day -> new ManagedMessages.Tier(day, Money.euroCents(100)))
                .toList();

        final String text = StoredDocumentsStandIn.renderer()
                .render(
                        ManagedMessages.CONTRIBUTION,
                        new ManagedMessages.Contribution(Money.euroCents(500)),
                        Map.of(
                                "tiers",
                                DocumentRenderer.FilledList.of(tiers),
                                "buy",
                                new DocumentRenderer.FilledButton(Ids.BUY)),
                        Locale.ENGLISH)
                .text();

        assertAll(
                () -> assertTrue(text.length() <= DocumentCheck.TEXT, text.length() + " characters"),
                () -> assertTrue(text.matches("(?s).*\n\\+\\d+(\n.*)?"), text.substring(text.length() - 200)));
    }
}
