package eu.nordtal.season.discordrendering;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.season.messages.Messages;
import eu.nordtal.season.messages.document.Document;
import eu.nordtal.season.messages.document.DocumentDeclaration;
import eu.nordtal.season.messages.document.DocumentSpec;
import eu.nordtal.season.messages.document.Documents;
import eu.nordtal.season.messages.document.Slot;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DocumentRendererTest {
    @Test
    void aSelectPreselectsOnlyTheMembersCountry() {
        final var options = List.of(
                new DocumentRenderer.Option("de", "Deutschland", Map.of(), null),
                new DocumentRenderer.Option("at", "Österreich", Map.of(), null));
        final var selected = SelectDrawing.draw(
                new DocumentRenderer.FilledSelect("countries", options, "at"), "Country", option -> option.label());
        assertEquals(
                List.of(false, true),
                selected.getOptions().stream().map(option -> option.isDefault()).toList());
        final var absent = SelectDrawing.draw(
                new DocumentRenderer.FilledSelect("countries", options), "Country", option -> option.label());
        assertEquals(
                List.of(false, false),
                absent.getOptions().stream().map(option -> option.isDefault()).toList());
    }

    @Test
    void aSelectAcceptsFormattedEmoji() {
        final var declaration = new DocumentDeclaration(
                "test",
                "countries",
                "Countries",
                false,
                "",
                DocumentSpec.Shape.MESSAGE,
                0,
                Map.of(),
                List.of(Slot.select("countries")));
        final var document = Document.decode("""
                {"parts":[{"type":"ROW","items":[{"slot":"countries","placeholder":{"en":"Country"}}]}]}
                """);
        final var renderer =
                new DocumentRenderer(DiscordRenderer.of(Messages.load("messages/rendering", Locale.ENGLISH)));
        final var drawn = renderer.draw(
                new Documents.Packaged(declaration, document),
                Map.of(),
                Map.of(
                        "countries",
                        new DocumentRenderer.FilledSelect(
                                "country",
                                List.of(new DocumentRenderer.Option(
                                        "de", "Deutschland", Map.of(), "<:flag:123456789012345678>")))),
                Locale.ENGLISH);
        final var tree = net.dv8tion.jda.api.components.tree.MessageComponentTree.of(drawn.components());
        final var option = tree.findAll(net.dv8tion.jda.api.components.selections.StringSelectMenu.class)
                .getFirst()
                .getOptions()
                .getFirst();
        assertEquals(
                net.dv8tion.jda.api.entities.emoji.Emoji.Type.CUSTOM,
                option.getEmoji().getType());
    }

    @Test
    void aContainerRepeatsItsTextInEachLanguage() {
        final var declaration = new DocumentDeclaration(
                "test",
                "welcome",
                "Welcome",
                false,
                "",
                DocumentSpec.Shape.MESSAGE,
                0,
                Map.of(),
                List.of(Slot.languages("languages")));
        final var document = Document.decode("""
                {"parts":[{"type":"CONTAINER","parts":[{"type":"LANGUAGES","slot":"languages","parts":[
                  {"type":"TEXT","lines":[{"text":{"en":"Welcome","de":"Willkommen"}}]}
                ]}]}]}
                """);
        final var renderer = new DocumentRenderer(
                DiscordRenderer.of(Messages.load("messages/rendering", Locale.ENGLISH, Locale.GERMAN)));
        final var drawn = renderer.draw(
                new Documents.Packaged(declaration, document),
                Map.of(),
                Map.of("languages", new DocumentRenderer.FilledLanguages(List.of(Locale.ENGLISH, Locale.GERMAN))),
                Locale.ENGLISH);
        assertEquals("Welcome\nWillkommen", drawn.text());
    }
}
