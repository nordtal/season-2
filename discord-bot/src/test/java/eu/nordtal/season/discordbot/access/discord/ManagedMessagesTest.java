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
import net.dv8tion.jda.api.components.Component;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import org.junit.jupiter.api.Test;

class ManagedMessagesTest {

    private static final DocumentRenderer RENDERER = StoredDocumentsStandIn.renderer();
    private static final Tiers TIERS = Tiers.of(List.of(new Tier(30, 500), new Tier(90, 1250)), 500);
    private static final GuildLanguages.Language GERMAN =
            new GuildLanguages.Language("de", "Deutsch", "2", "3", "", "5", "", "4");

    @Test
    void theJoinMessageCombinesAccessPricesAndAccountLinkInOneContainerWithBothButtons() {
        final DocumentRenderer.Drawn drawn = ManagedMessages.link(RENDERER, TIERS, Locale.ENGLISH);
        final Container box = (Container) drawn.components().getFirst();
        final List<Button> buttons = MessageComponentTree.of(drawn.components()).findAll(Button.class);

        assertAll(
                () -> assertEquals(1, drawn.components().size()),
                () -> assertInstanceOf(MediaGallery.class, box.getComponents().getFirst()),
                () -> assertEquals(List.of("link.png"), drawn.attachments()),
                () -> assertEquals(
                        "With access you can join `play.nordtal.eu`.\n**30 days** for €5.00\n**90 days** for €12.50",
                        ((TextDisplay) box.getComponents().get(2)).getContent()),
                () -> assertTrue(box.getComponents().stream().anyMatch(Separator.class::isInstance)),
                () -> assertEquals(
                        List.of(Ids.BUY, Ids.LINK),
                        buttons.stream().map(Button::getCustomId).toList()),
                () -> assertEquals(
                        List.of("Buy access", "Enter code"),
                        buttons.stream().map(Button::getLabel).toList()),
                () -> assertTrue(drawn.text().contains("Run `/unlink` to link a different Minecraft account.")));
    }

    @Test
    void theGermanJoinMessageShowsBothButtonsAndLinkSteps() {
        final DocumentRenderer.Drawn drawn = ManagedMessages.link(RENDERER, TIERS, Locale.GERMAN);
        final List<Button> buttons = MessageComponentTree.of(drawn.components()).findAll(Button.class);

        assertAll(
                () -> assertEquals(1, drawn.components().size()),
                () -> assertTrue(
                        drawn.text()
                                .contains(
                                        "1. Betritt `play.nordtal.eu` in Minecraft.\n2. Gib hier den Code vom Login-Bildschirm ein."),
                        drawn.text()),
                () -> assertEquals(
                        List.of("Zugang kaufen", "Code eingeben"),
                        buttons.stream().map(Button::getLabel).toList()));
    }

    @Test
    void theRulesAndSeasonOverviewShareOneContainerWithJoinAndNewsButtons() {
        final DocumentRenderer.Drawn drawn = ManagedMessages.rules(RENDERER, GERMAN, "100");
        final Container box = (Container) drawn.components().getFirst();
        final List<Button> buttons = MessageComponentTree.of(drawn.components()).findAll(Button.class);

        assertAll(
                () -> assertEquals(1, drawn.components().size()),
                () -> assertInstanceOf(MediaGallery.class, box.getComponents().getFirst()),
                () -> assertEquals(List.of("rules.png"), drawn.attachments()),
                () -> assertEquals(
                        "Diese Regeln gelten im Discord und im Spiel.\n1. Geh mit allen anständig um.\n2. Spiel fair."
                                + "\n3. Kämpfe auf der SMP nur gegen andere Spieler, wenn ihr beide zustimmt."
                                + "\n4. In den gemeinsamen Kanälen sind Deutsch und Englisch beide willkommen.",
                        ((TextDisplay) box.getComponents().get(2)).getContent()),
                () -> assertTrue(box.getComponents().stream().anyMatch(Separator.class::isInstance)),
                () -> assertTrue(drawn.text().contains("Schienennetz"), drawn.text()),
                () -> assertEquals(
                        List.of("Beitreten", "News"),
                        buttons.stream().map(Button::getLabel).toList()),
                () -> assertEquals(
                        List.of("https://discord.com/channels/100/2", "https://discord.com/channels/100/5"),
                        buttons.stream().map(Button::getUrl).toList()),
                () -> assertTrue(MessageComponentTree.of(drawn.components())
                                .findAll(Component.class)
                                .size()
                        <= DocumentCheck.COMPONENTS),
                () -> assertTrue(drawn.text().length() <= DocumentCheck.TEXT));
    }

    @Test
    void theRulesWithoutServerOrChannelsHideTheOptionalLinkButtons() {
        final DocumentRenderer.Drawn drawn = ManagedMessages.rules(RENDERER, GERMAN, "");

        assertAll(
                () -> assertEquals(
                        List.of(), MessageComponentTree.of(drawn.components()).findAll(Button.class)),
                () -> assertTrue(drawn.text().contains("1. Geh mit allen anständig um."), drawn.text()));
    }

    @Test
    void aDataListTooLongForOneMessageLosesItsLastItemsToACount() {
        final DocumentRenderer.Drawn drawn = tooManyTiers(Locale.ENGLISH);
        final int characters = MessageComponentTree.of(drawn.components()).findAll(TextDisplay.class).stream()
                .mapToInt(display -> display.getContent().length())
                .sum();
        final String text = drawn.text();

        assertAll(
                () -> assertTrue(characters <= DocumentCheck.TEXT, characters + " characters"),
                () -> assertTrue(
                        text.matches("(?s).*\n\\d+ more lines are not shown\\.(\n.*)?"),
                        text.substring(text.length() - 200)));
    }

    @Test
    void theCountOfWhatIsLeftOutIsWrittenInTheReadersLanguage() {
        final String text = tooManyTiers(Locale.GERMAN).text();

        assertTrue(
                text.matches("(?s).*\n\\d+ weitere Zeilen werden nicht angezeigt\\.(\n.*)?"),
                text.substring(text.length() - 200));
    }

    private static DocumentRenderer.Drawn tooManyTiers(final Locale locale) {
        final List<ManagedMessages.Tier> tiers = IntStream.range(1, 400)
                .mapToObj(day -> new ManagedMessages.Tier(day, Money.euroCents(100)))
                .toList();

        return StoredDocumentsStandIn.renderer()
                .render(
                        ManagedMessages.LINK,
                        new ManagedMessages.Contribution(Money.euroCents(500)),
                        Map.of(
                                "tiers",
                                DocumentRenderer.FilledList.of(tiers),
                                "buy",
                                new DocumentRenderer.FilledButton(Ids.BUY),
                                "link",
                                new DocumentRenderer.FilledButton(Ids.LINK)),
                        locale);
    }
}
