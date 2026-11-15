package eu.nordtal.season.discordbot.access.discord;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.payment.Tier;
import eu.nordtal.season.database.payment.Tiers;
import eu.nordtal.season.discordbot.Ids;
import eu.nordtal.season.discordbot.StoredDocumentsStandIn;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.messages.document.DocumentCheck;
import eu.nordtal.season.messages.value.Money;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import org.junit.jupiter.api.Test;

class ManagedMessagesTest {

    private static final DocumentRenderer RENDERER = StoredDocumentsStandIn.renderer();

    @Test
    void theContributionMessageListsEachTierUnderItsBannerInOneBoxWithTheBuyButton() {
        final DocumentRenderer.Drawn drawn = ManagedMessages.contribution(
                RENDERER, Tiers.of(List.of(new Tier(30, 500), new Tier(90, 1250)), 500), Locale.ENGLISH);
        final Container box = (Container) drawn.components().getFirst();
        final Button buy = MessageComponentTree.of(drawn.components())
                .findAll(Button.class)
                .getFirst();

        assertAll(
                () -> assertEquals(1, drawn.components().size()),
                () -> assertInstanceOf(MediaGallery.class, box.getComponents().getFirst()),
                () -> assertEquals(
                        "With access you can join the server.\n**30 days** for €5.00\n**90 days** for €12.50",
                        ((TextDisplay) box.getComponents().get(2)).getContent()),
                () -> assertEquals(List.of("contribution.png"), drawn.attachments()),
                () -> assertEquals(Ids.BUY, buy.getCustomId()),
                () -> assertEquals("Buy access", buy.getLabel()));
    }

    @Test
    void theLinkStepsStandOverTheButtonThatTakesTheCode() {
        final DocumentRenderer.Drawn drawn = ManagedMessages.link(RENDERER, Locale.GERMAN);
        final Container box = (Container) drawn.components().getFirst();

        assertAll(
                () -> assertEquals(1, drawn.components().size()),
                () -> assertEquals(
                        "1. Betritt `play.nordtal.eu` in Minecraft.\n2. Gib den Code ein, den dir der Server zeigt.",
                        ((TextDisplay) box.getComponents().get(2)).getContent()),
                () -> assertEquals(
                        "Code eingeben",
                        ((ActionRow) box.getComponents().get(3))
                                .getButtons()
                                .getFirst()
                                .getLabel()));
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
