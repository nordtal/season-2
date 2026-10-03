package eu.nordtal.s2.messagerendering.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.PlayerId;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.spec.Arg;
import eu.nordtal.s2.messages.spec.Display;
import eu.nordtal.s2.messages.spec.Format;
import eu.nordtal.s2.messages.spec.Key;
import eu.nordtal.s2.messages.spec.MessageSchema;
import eu.nordtal.s2.messages.spec.MessageSpec;
import eu.nordtal.s2.messages.spec.MessageSpecCheck;
import eu.nordtal.s2.messages.spec.MessageSpecs;
import eu.nordtal.s2.messages.spec.Name;
import eu.nordtal.s2.messages.spec.Shown;
import eu.nordtal.s2.messages.spec.TextFormat;
import eu.nordtal.s2.messages.value.DisplayName;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;

class MessageSpecsTest {

    @MessageSpec("spec-test")
    interface TestMessages {

        @Name("Greeting")
        MessageRef greeting(@Arg("player") String player);

        @Name("Duel")
        Duel duel();

        @Name("Screens")
        Screens screen();

        @Name("Chat")
        Chat chat();

        interface Duel {

            @Name("Won")
            MessageRef won(@Arg("opponent") String opponent);

            @Name("Lost")
            MessageRef lost();

            @Key("won")
            Won wonSection();
        }

        @Name("Won")
        interface Won {

            @Name("Title")
            MessageRef title();
        }

        interface Screens {

            @Name("Pack")
            Screen pack();

            @Name("Update")
            Screen update();

            /** How a spec maps an enum onto its own methods. */
            default Screen of(final boolean updating) {
                return updating ? update() : pack();
            }
        }

        interface Screen {

            @Name("Title")
            MessageRef title();

            @Name("Subtitle")
            MessageRef subtitle();
        }

        interface Chat {

            @Name("Line")
            MessageRef line(@Arg("sender") DisplayName sender, @Arg("message") String message);
        }
    }

    private final TestMessages messages = MessageSpecs.create(TestMessages.class);

    @Test
    void aMethodIsItsKeyAndItsArgumentsArePlaceholders() {
        assertEquals(new MessageRef("greeting", Map.of("player", "Till")), messages.greeting("Till"));
        assertEquals(
                new MessageRef("duel.won", Map.of("opponent", "Ada")),
                messages.duel().won("Ada"));
        assertEquals(
                MessageRef.of("duel.won.title"), messages.duel().wonSection().title());
    }

    @Test
    void theSectionPrefixComesFromTheAccessorNotTheType() {
        assertEquals("screen.pack.title", messages.screen().pack().title().key());
        assertEquals(
                "screen.update.subtitle", messages.screen().update().subtitle().key());
        assertEquals("screen.update.title", messages.screen().of(true).title().key());
        assertSame(messages.duel(), messages.duel());
    }

    @Test
    void theSchemaFollowsTheEnglishFileAndNamesEverySection() {
        final List<MessageSchema.Entry> entries = MessageSchema.entries(TestMessages.class);
        assertEquals(
                List.of(
                        "greeting",
                        "duel.won",
                        "duel.won.title",
                        "duel.lost",
                        "screen.pack.title",
                        "screen.pack.subtitle",
                        "screen.update.title",
                        "screen.update.subtitle",
                        "chat.line"),
                entries.stream().map(MessageSchema.Entry::key).toList());
        final MessageSchema.Entry title = entries.get(2);
        assertEquals(List.of("Duel", "Won"), title.section());
        assertEquals(List.of("Screens", "Update"), entries.get(6).section());
        assertEquals(
                List.of(
                        new MessageSchema.Arg("sender", "name", null, "Alex", false),
                        new MessageSchema.Arg("message", "text", null, "Nordtal", false)),
                entries.get(8).args());
        assertTrue(MessageSchema.json(TestMessages.class)
                .contains(
                        "{\"key\":\"greeting\",\"name\":\"Greeting\",\"args\":[{\"name\":\"player\",\"kind\":\"text\","
                                + "\"example\":\"Nordtal\",\"action\":false}],"
                                + "\"section\":[],\"format\":\"MINIMESSAGE\",\"shown\":\"CHAT\"}"));
    }

    @Test
    void aSpecThatMatchesItsBundleHasNoProblems() {
        assertEquals(List.of(), MessageSpecCheck.problems(TestMessages.class));
    }

    @MessageSpec("spec-test")
    interface Drifted {

        MessageRef greeting(@Arg("name") String player);

        @Name("Lost")
        @Key("duel.lost")
        MessageRef lost(@Arg("opponent") String opponent);

        @Name("Chat line")
        @Key("chat.line")
        MessageRef line(@Arg("sender") DisplayName sender, @Arg("other") String other);
    }

    @Test
    void driftIsNamedKeyByKey() {
        final List<String> problems = MessageSpecCheck.problems(Drifted.class);
        assertTrue(problems.contains("greeting: no @Name"), problems::toString);
        assertTrue(
                problems.stream().anyMatch(problem -> problem.startsWith("greeting (en): {player} is nothing")),
                problems::toString);
        assertTrue(
                problems.contains("greeting (en): the text never shows name, which the message is given"),
                problems::toString);
        assertTrue(
                problems.contains("duel.lost (de): the text never shows opponent, which the message is given"),
                problems::toString);
        assertTrue(
                problems.contains("chat.line (en): the text never shows other, which the message is given"),
                problems::toString);
        assertTrue(problems.contains("duel.won: in en.properties, but no method declares it"), problems::toString);
    }

    @MessageSpec("spec-test")
    @Shown(Display.DISCORD_MESSAGE)
    @Format(TextFormat.DISCORD_MARKDOWN)
    interface Annotated {

        @Name("Lost")
        @Key("duel.lost")
        MessageRef lost();
    }

    @Test
    void formatAndPlaceOnTheSpecItselfReachTheSchema() {
        final MessageSchema.Entry lost = MessageSchema.entries(Annotated.class).getFirst();
        assertEquals(TextFormat.DISCORD_MARKDOWN, lost.format());
        assertEquals(Display.DISCORD_MESSAGE, lost.shown());
    }

    @Test
    void theRendererInsertsEveryValueAndEscapesTheText() {
        final MessageRenderer renderer = MessageRenderer.of(Messages.load("messages/spec-test"));
        final Component line = renderer.format(
                Locale.GERMAN, messages.chat().line(new DisplayName(PlayerId.of(new UUID(0, 1)), "Ada"), "<red>| hi"));
        assertEquals("Ada: <red>| hi", plain(line));
    }

    private static String plain(final Component component) {
        final StringBuilder out = new StringBuilder();
        if (component instanceof final TextComponent text) {
            out.append(text.content());
        }
        component.children().forEach(child -> out.append(plain(child)));
        return out.toString();
    }
}
