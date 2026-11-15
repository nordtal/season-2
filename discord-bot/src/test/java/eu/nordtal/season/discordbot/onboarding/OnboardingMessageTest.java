package eu.nordtal.season.discordbot.onboarding;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.language.Languages;
import eu.nordtal.season.discordbot.Ids;
import eu.nordtal.season.discordbot.Mark;
import eu.nordtal.season.discordbot.StoredDocumentsStandIn;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.discordrendering.DiscordRenderer;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import eu.nordtal.season.messages.document.DocumentSpec;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import org.junit.jupiter.api.Test;

class OnboardingMessageTest {

    private static final GuildLanguages LANGUAGES = GuildLanguages.of(
            List.of(
                    new GuildLanguages.Language("en", "English", "", "", "", ""),
                    new GuildLanguages.Language("de", "Sprache Deutsch", "", "", "", "")),
            new Languages(List.of("en", "de")));

    private static final DiscordRenderer RENDERER =
            StoredDocumentsStandIn.renderer().messages();

    private final OnboardingMessage message = new OnboardingMessage(LANGUAGES, RENDERER, StoredDocumentsStandIn.none());

    @Test
    void theWelcomeIsOneBoxInTheDefaultLanguageUnderItsBanner() {
        final DocumentRenderer.Drawn welcome = message.welcome();
        final Container box = (Container) welcome.components().getFirst();

        assertAll(
                () -> assertEquals(1, welcome.components().size()),
                () -> assertEquals(
                        "attachment://onboarding.png",
                        ((MediaGallery) box.getComponents().getFirst())
                                .getItems()
                                .getFirst()
                                .getUrl()),
                () -> assertEquals(
                        "## Welcome to Nordtal",
                        ((TextDisplay) box.getComponents().get(1)).getContent()),
                () -> assertEquals(List.of("onboarding.png"), welcome.attachments()));
    }

    @Test
    void theWelcomeAsksOnlyForTheLanguageWithOneSelectAndNoButton() {
        final MessageComponentTree welcome =
                MessageComponentTree.of(message.welcome().components());

        assertAll(
                () -> assertEquals(1, welcome.findAll(ActionRow.class).size()),
                () -> assertEquals(List.of(), welcome.findAll(Button.class)),
                () -> assertEquals(
                        Ids.ONBOARD_PICK_LANGUAGE,
                        select(message.welcome().components()).getCustomId()));
    }

    @Test
    void eachLanguageIsOfferedUnderItsOwnNameWithTheFlagOfItsCountry() {
        final List<SelectOption> options =
                select(message.welcome().components()).getOptions();

        assertAll(
                () -> assertEquals(
                        List.of("en", "de"),
                        options.stream().map(SelectOption::getValue).toList()),
                () -> assertEquals(
                        List.of("English", "Deutsch"),
                        options.stream().map(SelectOption::getLabel).toList()),
                () -> assertEquals(
                        List.of(Mark.flag("GB"), Mark.flag("DE")),
                        options.stream()
                                .map(option -> option.getEmoji().getName())
                                .toList()));
    }

    @Test
    void theChangeMessageIsOneBoxPerLanguageThatHoldsItsButtonEachInItsLanguage() {
        final List<MessageTopLevelComponent> change = message.change().components();
        final List<Button> buttons = MessageComponentTree.of(change).findAll(Button.class);

        assertAll(
                () -> assertEquals(2, change.size()),
                () -> assertTrue(change.stream().allMatch(Container.class::isInstance), change::toString),
                () -> assertEquals(
                        List.of(Ids.ONBOARD + "en", Ids.ONBOARD + "de"),
                        buttons.stream().map(Button::getCustomId).toList()),
                () -> assertEquals(
                        List.of("Change language and region", "Sprache und Region ändern"),
                        buttons.stream().map(Button::getLabel).toList()),
                () -> assertEquals(
                        "## Sprache und Region",
                        ((TextDisplay) ((Container) change.getLast())
                                        .getComponents()
                                        .getFirst())
                                .getContent()));
    }

    @Test
    void theRegionQuestionIsOneBoxInTheChosenLanguageThatHoldsItsSelect() {
        final DocumentRenderer.Drawn region = OnboardingFlow.region(
                StoredDocumentsStandIn.renderer(),
                Ids.ONBOARD_PICK_REGION + "de",
                List.of(SelectOption.of("Mitteleuropa", "Europe/Berlin")),
                Locale.GERMAN);

        assertAll(
                () -> assertEquals(1, region.components().size()),
                () -> assertEquals(
                        "Wähle die Region, aus der du spielst.\nNordtal zeigt jede Zeit in ihrer Zeitzone.",
                        region.text()),
                () -> assertEquals(
                        Ids.ONBOARD_PICK_REGION + "de",
                        ((StringSelectMenu) ((ActionRow)
                                                ((Container) region.components().getFirst())
                                                        .getComponents()
                                                        .getLast())
                                        .getComponents()
                                        .getFirst())
                                .getCustomId()));
    }

    private static StringSelectMenu select(final List<MessageTopLevelComponent> components) {
        return MessageComponentTree.of(components)
                .findAll(StringSelectMenu.class)
                .getFirst();
    }

    @Test
    void aBlockPerLanguageLosesItsLastLanguagesBeyondFortyComponents() {
        final DocumentRenderer.Drawn drawn = StoredDocumentsStandIn.renderer()
                .render(
                        OnboardingMessage.CHANGE,
                        new DocumentSpec.None(),
                        Map.of(
                                "each", new DocumentRenderer.FilledLanguages(Collections.nCopies(15, Locale.GERMAN)),
                                "change", new DocumentRenderer.FilledButton(Ids.ONBOARD)),
                        Locale.ENGLISH);

        assertEquals(10, drawn.components().size());
    }
}
