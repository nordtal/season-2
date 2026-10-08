package eu.nordtal.season.discordbot.onboarding;

import static eu.nordtal.season.discordbot.AccessMessages.MESSAGES;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.language.Languages;
import eu.nordtal.season.discordbot.DiscordRenderer;
import eu.nordtal.season.discordbot.Ids;
import eu.nordtal.season.discordbot.ManagedMessage;
import eu.nordtal.season.discordbot.Mark;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.messages.Messages;
import java.util.List;
import java.util.Locale;
import net.dv8tion.jda.api.components.Component;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
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
    void theWelcomeIsOneEmbedInTheDefaultLanguageWithItsBanner() {
        final ManagedMessage.Content welcome = message.welcome();

        assertAll(
                () -> assertEquals(1, welcome.embeds().size()),
                () -> assertEquals(
                        RENDERER.format(
                                Locale.ENGLISH, MESSAGES.onboarding().welcome().title()),
                        welcome.embeds().getFirst().getTitle()),
                () -> assertEquals(OnboardingMessage.BANNER, welcome.banner()),
                () -> assertEquals(
                        "attachment://" + OnboardingMessage.BANNER,
                        welcome.embeds().getFirst().getImage().getUrl()));
    }

    @Test
    void theWelcomeAsksOnlyForTheLanguageWithOneSelectAndNoButton() {
        final List<Component> components = components(message.welcome());

        assertEquals(1, components.size());
        final StringSelectMenu menu = (StringSelectMenu) components.getFirst();
        assertEquals(Ids.ONBOARD_PICK_LANGUAGE, menu.getCustomId());
    }

    @Test
    void eachLanguageIsOfferedUnderItsOwnNameWithTheFlagOfItsCountry() {
        final List<SelectOption> options =
                ((StringSelectMenu) components(message.welcome()).getFirst()).getOptions();

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
    void theChangeMessageHasAButtonPerLanguageThatOpensTheDialogInIt() {
        final ManagedMessage.Content change = message.change();

        assertAll(
                () -> assertEquals(2, change.embeds().size()),
                () -> assertEquals(
                        List.of(Ids.ONBOARD + "en", Ids.ONBOARD + "de"),
                        components(change).stream()
                                .map(component -> ((Button) component).getCustomId())
                                .toList()),
                () -> assertTrue(components(change).stream().allMatch(Button.class::isInstance)));
    }

    private static List<Component> components(final ManagedMessage.Content content) {
        return content.components().stream()
                .flatMap(row -> row.getComponents().stream())
                .map(Component.class::cast)
                .toList();
    }
}
