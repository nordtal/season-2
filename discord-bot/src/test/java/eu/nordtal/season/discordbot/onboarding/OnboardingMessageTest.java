package eu.nordtal.season.discordbot.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.language.Languages;
import eu.nordtal.season.discordbot.Ids;
import eu.nordtal.season.discordbot.Mark;
import eu.nordtal.season.discordbot.StoredDocumentsStandIn;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import java.util.List;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;
import org.junit.jupiter.api.Test;

class OnboardingMessageTest {
    private static final GuildLanguages LANGUAGES = GuildLanguages.of(
            List.of(
                    new GuildLanguages.Language("en", "English", "", "", "", "", "", "", "123"),
                    new GuildLanguages.Language("de", "Deutsch", "", "", "", "", "", "", "456")),
            new Languages(List.of("en", "de")));
    private static final Choices CHOICES = new Choices(
            List.of(),
            List.of(
                    new Choices.Choice(
                            Choices.Kind.COUNTRY,
                            "de",
                            "Deutschland",
                            "de",
                            "Europe/Berlin",
                            "de",
                            Mark.flag("DE"),
                            "flag-germany"),
                    new Choices.Choice(
                            Choices.Kind.COUNTRY,
                            "at",
                            "Österreich",
                            "at",
                            "Europe/Vienna",
                            "de",
                            Mark.flag("AT"),
                            "flag-austria")),
            "Lock");

    @Test
    void theStartMessageHasOneBoxOneBannerBothLanguagesAndTheCountrySelect() {
        final var drawn = new OnboardingMessage(
                        LANGUAGES, StoredDocumentsStandIn.renderer().messages(), StoredDocumentsStandIn.none())
                .welcome(CHOICES);
        final var tree = MessageComponentTree.of(drawn.components());
        assertEquals(1, tree.findAll(Container.class).size());
        assertEquals(List.of("onboarding.png"), drawn.attachments());
        assertTrue(drawn.text().contains("Choose your country."));
        assertTrue(drawn.text().contains("Wähle dein Land."));
        final var select = tree.findAll(StringSelectMenu.class).getFirst();
        assertEquals(Ids.ONBOARD_COUNTRY, select.getCustomId());
        assertEquals(
                List.of("Deutschland", "Österreich"),
                select.getOptions().stream().map(option -> option.getLabel()).toList());
        assertEquals(Ids.ONBOARD_SETTINGS, tree.findAll(Button.class).getFirst().getCustomId());
    }

    @Test
    void settingsShowLanguageZoneRulesAndOnlyTheOtherLanguageButton() {
        final var drawn = OnboardingFlow.settings(
                StoredDocumentsStandIn.renderer(),
                LANGUAGES,
                CHOICES,
                LANGUAGES.byTag("de").orElseThrow(),
                "at",
                "Europe/Vienna",
                "789");
        final var tree = MessageComponentTree.of(drawn.components());
        assertTrue(drawn.text().contains("Sprache: **Deutsch**"));
        assertTrue(drawn.text().contains("Zeitzone: **Europe/Vienna**"));
        assertEquals(
                List.of(false, true),
                tree.findAll(StringSelectMenu.class).getFirst().getOptions().stream()
                        .map(option -> option.isDefault())
                        .toList());
        assertEquals(
                "https://discord.com/channels/789/456",
                tree.findAll(Button.class).getFirst().getUrl());
        assertEquals(
                Ids.ONBOARD_LANGUAGE + "en",
                tree.findAll(Button.class).getLast().getCustomId());
    }

    @Test
    void settingsWithoutACountryPreselectNothing() {
        final var drawn = OnboardingFlow.settings(
                StoredDocumentsStandIn.renderer(),
                LANGUAGES,
                CHOICES,
                LANGUAGES.fallback(),
                null,
                "Europe/Berlin",
                "789");
        assertTrue(
                MessageComponentTree.of(drawn.components())
                        .findAll(StringSelectMenu.class)
                        .getFirst()
                        .getOptions()
                        .stream()
                        .noneMatch(option -> option.isDefault()));
    }
}
