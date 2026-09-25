package eu.nordtal.s2.common.message.spec;

import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageSpecsTest {

    @MessageSpec("spec-test")
    interface TestMessages {

        @Name("Greeting")
        MessageRef greeting(@Arg("player") Object player);

        @Name("Duel")
        Duel duel();

        @Name("Screens")
        Screens screen();

        @Name("Chat")
        Chat chat();

        interface Duel {

            @Name("Won")
            MessageRef won(@Arg("opponent") Object opponent);

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
            MessageRef line(@Arg("_sender") Component sender, @Arg("separator") Object separator,
                            @Arg("_message") Component message);
        }
    }

    private final TestMessages messages = MessageSpecs.create(TestMessages.class);

    @Test
    void aMethodIsItsKeyAndItsArgumentsArePlaceholders() {
        assertEquals(new MessageRef("greeting", Map.of("player", "Till")), messages.greeting("Till"));
        assertEquals(new MessageRef("duel.won", Map.of("opponent", "Ada")), messages.duel().won("Ada"));
        assertEquals(MessageRef.of("duel.won.title"), messages.duel().wonSection().title());
    }

    @Test
    void theSectionPrefixComesFromTheAccessorNotTheType() {
        assertEquals("screen.pack.title", messages.screen().pack().title().key());
        assertEquals("screen.update.subtitle", messages.screen().update().subtitle().key());
        assertEquals("screen.update.title", messages.screen().of(true).title().key());
        assertSame(messages.duel(), messages.duel());
    }

    @Test
    void theSchemaFollowsTheEnglishFileAndNamesEverySection() {
        final List<MessageSchema.Entry> entries = MessageSchema.entries(TestMessages.class);
        assertEquals(List.of("greeting", "duel.won", "duel.won.title", "duel.lost", "screen.pack.title",
                        "screen.pack.subtitle", "screen.update.title", "screen.update.subtitle", "chat.line"),
                entries.stream().map(MessageSchema.Entry::key).toList());
        final MessageSchema.Entry title = entries.get(2);
        assertEquals(List.of("Duel", "Won"), title.section());
        assertEquals(List.of("Screens", "Update"), entries.get(6).section());
        assertEquals(List.of(new MessageSchema.Arg("_sender", true), new MessageSchema.Arg("separator", false),
                new MessageSchema.Arg("_message", true)), entries.get(8).args());
        assertTrue(MessageSchema.json(TestMessages.class).contains(
                "{\"key\": \"greeting\", \"name\": \"Greeting\", \"format\": \"MINIMESSAGE\", \"shown\": \"CHAT\", \"args\": [{\"name\": \"player\", \"component\": false}], \"section\": []}"));
    }

    @Test
    void aSpecThatMatchesItsBundleHasNoProblems() {
        assertEquals(List.of(), MessageSpecCheck.problems(TestMessages.class));
    }

    @MessageSpec("spec-test")
    interface Drifted {

        MessageRef greeting(@Arg("name") Object player);

        @Name("Lost")
        @Key("duel.lost")
        MessageRef lost(@Arg("opponent") Object opponent);

        @Name("Chat line")
        @Key("chat.line")
        MessageRef line(@Arg("_sender") Component sender, @Arg("separator") Object separator,
                        @Arg("_other") Component other);
    }

    @Test
    void driftIsNamedKeyByKey() {
        final List<String> problems = MessageSpecCheck.problems(Drifted.class);
        assertTrue(problems.contains("greeting: no @Name"), problems::toString);
        assertTrue(problems.contains("greeting (en): the text names [player], the method declares [name]"),
                problems::toString);
        assertTrue(problems.contains("duel.lost (de): the text names [], the method declares [opponent]"),
                problems::toString);
        assertTrue(problems.contains("chat.line (en): the method declares <_other>, the text never uses it"),
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
    void kebabCaseSplitsWordsAndDigitRuns() {
        assertEquals("no-such-member", MessageSpecs.kebab("noSuchMember"));
        assertEquals("tier-12-hours", MessageSpecs.kebab("tier12Hours"));
    }

    @Test
    void theRendererFillsComponentsAsTagsAndEscapesTheRest() {
        final MessageRenderer renderer = MessageRenderer.of(Messages.load("messages/spec-test"));
        final Component line = renderer.format(Locale.GERMAN, messages.chat().line(
                Component.text("Ada"), "<red>|", Component.text("hi")));
        assertEquals("Ada <red>| hi", plain(line));
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
