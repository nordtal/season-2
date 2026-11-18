package eu.nordtal.season.discordbot.access.discord;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.database.payment.Tier;
import eu.nordtal.season.database.payment.Tiers;
import eu.nordtal.season.discordbot.Ids;
import eu.nordtal.season.discordbot.StoredDocumentsStandIn;
import eu.nordtal.season.discordbot.config.GuildLanguages;
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
    private static final GuildLanguages.Language GERMAN =
            new GuildLanguages.Language("de", "Deutsch", "1", "2", "3", "", "", "", "4");

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
                        "With access you can join `play.nordtal.eu`.\n**30 days** for €5.00\n**90 days** for €12.50",
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
                        "1. Betritt `play.nordtal.eu` in Minecraft.\n2. Gib hier den Code vom Login-Bildschirm ein.",
                        ((TextDisplay) box.getComponents().get(2)).getContent()),
                () -> assertEquals(
                        "Code eingeben",
                        ((ActionRow) box.getComponents().get(3))
                                .getButtons()
                                .getFirst()
                                .getLabel()));
    }

    @Test
    void theRulesStandUnderTheirBannerAsAListThatWelcomesBothLanguages() {
        final DocumentRenderer.Drawn drawn = ManagedMessages.rules(RENDERER, Locale.ENGLISH);
        final Container box = (Container) drawn.components().getFirst();

        assertAll(
                () -> assertEquals(1, drawn.components().size()),
                () -> assertInstanceOf(MediaGallery.class, box.getComponents().getFirst()),
                () -> assertEquals(List.of("rules.png"), drawn.attachments()),
                () -> assertEquals(
                        "These rules apply on Discord and in the game.\n1. Treat everyone decently.\n2. Play fair."
                                + "\n3. Fight other players on the SMP only when both of you agree."
                                + "\n4. German and English are both welcome in the shared channels.",
                        ((TextDisplay) box.getComponents().get(2)).getContent()));
    }

    @Test
    void eachStepOfTheGuideOpensItsChannelInTheSameLanguage() {
        final DocumentRenderer.Drawn drawn = ManagedMessages.guide(RENDERER, GERMAN, "100");
        final List<Button> buttons = MessageComponentTree.of(drawn.components()).findAll(Button.class);

        assertAll(
                () -> assertEquals(1, drawn.components().size()),
                () -> assertEquals(List.of(), drawn.attachments()),
                () -> assertEquals(
                        List.of("Konto verknüpfen", "Zugang kaufen"),
                        buttons.stream().map(Button::getLabel).toList()),
                () -> assertEquals(
                        List.of("https://discord.com/channels/100/2", "https://discord.com/channels/100/1"),
                        buttons.stream().map(Button::getUrl).toList()),
                () -> assertTrue(
                        drawn.text().contains("3. Verbinde dich in Minecraft mit `play.nordtal.eu`."), drawn.text()));
    }

    @Test
    void aGuideWithoutItsServerShowsTheStepsWithoutButtons() {
        final DocumentRenderer.Drawn drawn = ManagedMessages.guide(RENDERER, GERMAN, "");

        assertAll(
                () -> assertEquals(
                        List.of(), MessageComponentTree.of(drawn.components()).findAll(Button.class)),
                () -> assertTrue(drawn.text().contains("1. Verknüpfe dein Minecraft-Konto."), drawn.text()));
    }

    @Test
    void aDataListTooLongForOneMessageLosesItsLastItemsToACount() {
        final String text = tooManyTiers(Locale.ENGLISH);

        assertAll(
                () -> assertTrue(text.length() <= DocumentCheck.TEXT, text.length() + " characters"),
                () -> assertTrue(
                        text.matches("(?s).*\n\\d+ more lines are not shown\\.(\n.*)?"),
                        text.substring(text.length() - 200)));
    }

    @Test
    void theCountOfWhatIsLeftOutIsWrittenInTheReadersLanguage() {
        final String text = tooManyTiers(Locale.GERMAN);

        assertTrue(
                text.matches("(?s).*\n\\d+ weitere Zeilen werden nicht angezeigt\\.(\n.*)?"),
                text.substring(text.length() - 200));
    }

    private static String tooManyTiers(final Locale locale) {
        final List<ManagedMessages.Tier> tiers = IntStream.range(1, 400)
                .mapToObj(day -> new ManagedMessages.Tier(day, Money.euroCents(100)))
                .toList();

        return StoredDocumentsStandIn.renderer()
                .render(
                        ManagedMessages.CONTRIBUTION,
                        new ManagedMessages.Contribution(Money.euroCents(500)),
                        Map.of(
                                "tiers",
                                DocumentRenderer.FilledList.of(tiers),
                                "buy",
                                new DocumentRenderer.FilledButton(Ids.BUY)),
                        locale)
                .text();
    }
}
