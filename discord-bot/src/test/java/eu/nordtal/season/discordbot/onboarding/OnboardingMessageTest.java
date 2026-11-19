package eu.nordtal.season.discordbot.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.season.common.language.Languages;
import eu.nordtal.season.discordbot.Ids;
import eu.nordtal.season.discordbot.Mark;
import eu.nordtal.season.discordbot.StoredDocumentsStandIn;
import eu.nordtal.season.discordbot.config.GuildLanguages;
import eu.nordtal.season.discordrendering.DocumentRenderer;
import java.util.List;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
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
    void theChooseMessageIsEnglishOnlyWithOneBoxOneBannerAndTheCountrySelectWithoutAChoice() {
        final var drawn = message().welcome(CHOICES);
        final var tree = MessageComponentTree.of(drawn.components());
        assertEquals(1, tree.findAll(Container.class).size());
        assertEquals(List.of("onboarding.png"), drawn.attachments());
        assertTrue(drawn.text().contains("Choose your country to start."));
        assertFalse(drawn.text().contains("Wähle"));
        final var select = tree.findAll(StringSelectMenu.class).getFirst();
        assertEquals(Ids.ONBOARD_COUNTRY, select.getCustomId());
        assertEquals(
                List.of("Deutschland", "Österreich"),
                select.getOptions().stream().map(option -> option.getLabel()).toList());
        assertTrue(select.getOptions().stream().noneMatch(option -> option.isDefault()));
        assertTrue(tree.findAll(Button.class).isEmpty());
    }

    @Test
    void theChangeMessageHasATextAndOneShowSettingsButtonInEnglishOnly() {
        final var drawn = message().change();
        final var tree = MessageComponentTree.of(drawn.components());
        assertTrue(drawn.text().contains("Change your country or language here."));
        assertFalse(drawn.text().contains("Hier änderst du"));
        assertTrue(tree.findAll(StringSelectMenu.class).isEmpty());
        assertEquals(
                List.of(Ids.ONBOARD_SETTINGS),
                tree.findAll(Button.class).stream().map(Button::getCustomId).toList());
        assertEquals(
                List.of("Show settings"),
                tree.findAll(Button.class).stream().map(Button::getLabel).toList());
    }

    @Test
    void theConfirmDialogIsInTheDraftLanguageWithBothSelectsPresetAndAGreenConfirm() {
        final Draft draft = Draft.of(CHOICES.byValue(Choices.Kind.COUNTRY, "at").orElseThrow());
        final var drawn = OnboardingFlow.dialog(StoredDocumentsStandIn.renderer(), LANGUAGES, CHOICES, draft);
        final var tree = MessageComponentTree.of(drawn.components());
        final List<StringSelectMenu> selects = tree.findAll(StringSelectMenu.class);
        assertTrue(drawn.text().contains("Prüfe dein Land und deine Sprache"), drawn.text());
        assertFalse(drawn.text().contains("gespeichert"));
        assertEquals(List.of("at"), defaults(selects.getFirst()));
        assertEquals(List.of("de"), defaults(selects.getLast()));
        assertEquals(draft.id(Draft.Field.LANGUAGE), selects.getLast().getCustomId());
        assertEquals(
                List.of("\uD83C\uDF10", "\uD83C\uDDE9\uD83C\uDDEA"),
                selects.getLast().getOptions().stream()
                        .map(option -> option.getEmoji().getFormatted())
                        .toList());
        final Button confirm = tree.findAll(Button.class).getFirst();
        assertEquals(ButtonStyle.SUCCESS, confirm.getStyle());
        assertEquals(draft.id(Draft.Field.CONFIRM), confirm.getCustomId());
        assertEquals("Bestätigen", confirm.getLabel());
    }

    @Test
    void theSettingsDialogShowsOnlyTheChoicesAsSelectsSavesWithoutConfirmAndSaysWhenSaved() {
        final var opened = settings(false);
        final var saved = settings(true);
        final var tree = MessageComponentTree.of(saved.components());
        final List<StringSelectMenu> selects = tree.findAll(StringSelectMenu.class);
        assertFalse(opened.text().contains("Deine Änderung ist gespeichert."));
        assertTrue(saved.text().contains("Deine Änderung ist gespeichert."), saved.text());
        assertFalse(saved.text().contains("Deutsch"), "the selects alone show the choices");
        assertEquals(Ids.ONBOARD_SET_COUNTRY, selects.getFirst().getCustomId());
        assertEquals(Ids.ONBOARD_SET_LANGUAGE, selects.getLast().getCustomId());
        assertEquals(List.of("at"), defaults(selects.getFirst()));
        assertEquals(List.of("de"), defaults(selects.getLast()));
        assertTrue(tree.findAll(Button.class).isEmpty(), "the settings dialog has no confirm and no rules link");
    }

    @Test
    void theSettingsDialogOfAMemberWithoutChoicesPresetsNothing() {
        final var drawn = OnboardingFlow.settings(
                StoredDocumentsStandIn.renderer(), LANGUAGES, CHOICES, LANGUAGES.fallback(), null, null, false);
        assertTrue(MessageComponentTree.of(drawn.components()).findAll(StringSelectMenu.class).stream()
                .allMatch(select -> defaults(select).isEmpty()));
    }

    private static OnboardingMessage message() {
        return new OnboardingMessage(StoredDocumentsStandIn.renderer().messages(), StoredDocumentsStandIn.none());
    }

    private static DocumentRenderer.Drawn settings(final boolean saved) {
        return OnboardingFlow.settings(
                StoredDocumentsStandIn.renderer(),
                LANGUAGES,
                CHOICES,
                LANGUAGES.byTag("de").orElseThrow(),
                "at",
                "de",
                saved);
    }

    private static List<String> defaults(final StringSelectMenu select) {
        return select.getOptions().stream()
                .filter(option -> option.isDefault())
                .map(option -> option.getValue())
                .toList();
    }
}
