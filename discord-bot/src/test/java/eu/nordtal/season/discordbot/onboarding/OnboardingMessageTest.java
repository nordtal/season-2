package eu.nordtal.season.discordbot.onboarding;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.common.language.Languages;
import eu.nordtal.season.discordbot.DiscordRenderer;
import eu.nordtal.season.discordbot.Ids;
import eu.nordtal.season.discordbot.LayoutRenderer;
import eu.nordtal.season.discordbot.Mark;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.messages.Messages;
import java.util.List;
import java.util.Locale;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import org.junit.jupiter.api.Test;

/** The two onboarding messages: the welcome that asks a locked member for a language, and the one to change later. */
class OnboardingMessageTest {

    private static final GuildLanguages LANGUAGES = GuildLanguages.of(
            List.of(
                    new GuildLanguages.Language("en", "English", "", "", "", ""),
                    new GuildLanguages.Language("de", "Sprache Deutsch", "", "", "", "")),
            new Languages(List.of("en", "de")));

    private static final DiscordRenderer RENDERER =
            DiscordRenderer.of(Messages.load("messages/access", Locale.ENGLISH, Locale.GERMAN));

    private final OnboardingMessage message = new OnboardingMessage(LANGUAGES, RENDERER);

    @Test
    void theWelcomeIsOneBoxInTheDefaultLanguageOverItsBanner() {
        final LayoutRenderer.Drawn welcome = message.welcome();
        final Container box = (Container) welcome.components().getFirst();

        assertAll(
                () -> assertEquals(
                        "## Welcome to Nordtal",
                        ((TextDisplay) box.getComponents().getFirst()).getContent()),
                () -> assertEquals(
                        "attachment://onboarding.png",
                        ((MediaGallery) box.getComponents().getLast())
                                .getItems()
                                .getFirst()
                                .getUrl()),
                () -> assertEquals(List.of("onboarding.png"), welcome.attachments()));
    }

    @Test
    void theWelcomeAsksOnlyForTheLanguageWithOneSelectAndNoButton() {
        final List<MessageTopLevelComponent> components = message.welcome().components();

        assertAll(
                () -> assertEquals(2, components.size()),
                () -> assertEquals(Ids.ONBOARD_PICK_LANGUAGE, select(components).getCustomId()));
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
    void theChangeMessageHasABoxAndAButtonPerLanguageEachInItsLanguage() {
        final List<MessageTopLevelComponent> change = message.change().components();
        final List<Button> buttons = change.stream()
                .filter(ActionRow.class::isInstance)
                .flatMap(row -> ((ActionRow) row).getButtons().stream())
                .toList();

        assertAll(
                () -> assertEquals(4, change.size()),
                () -> assertEquals(
                        List.of(Ids.ONBOARD + "en", Ids.ONBOARD + "de"),
                        buttons.stream().map(Button::getCustomId).toList()),
                () -> assertEquals(
                        List.of("Change", "Ändern"),
                        buttons.stream().map(Button::getLabel).toList()),
                () -> assertEquals(
                        "## Sprache und Region\n### Ändern\n**Ändern** → Sprache und Region, jederzeit",
                        ((TextDisplay) ((Container) change.get(2))
                                        .getComponents()
                                        .getFirst())
                                .getContent()));
    }

    private static StringSelectMenu select(final List<MessageTopLevelComponent> components) {
        return (StringSelectMenu)
                ((ActionRow) components.getLast()).getComponents().getFirst();
    }
}
