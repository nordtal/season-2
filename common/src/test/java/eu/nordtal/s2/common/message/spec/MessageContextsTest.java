package eu.nordtal.s2.common.message.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.MessageRenderer;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.context.Contexts;
import eu.nordtal.s2.common.message.context.PlayerContext;
import eu.nordtal.s2.common.message.context.TeamContext;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.junit.jupiter.api.Test;

class MessageContextsTest {

    @MessageSpec(value = "context-test", shown = Display.ACTION_BAR)
    interface Fight {

        @Name("Hit")
        @Shown(Display.TITLE)
        MessageRef hit(
                @Arg("attacker") PlayerContext attacker,
                @Arg("victim") PlayerContext victim,
                @Arg("damage") Object damage);

        @Name("Bar")
        Fight.Bar bar();

        @Shown(Display.BOSS_BAR)
        interface Bar {

            @Name("Progress")
            MessageRef progress(@Arg("percent") Object percent);

            @Name("Button")
            @Shown(Display.DISCORD_BUTTON)
            @Format(TextFormat.PLAIN)
            MessageRef button(@Arg("team") TeamContext team);
        }
    }

    private final Fight fight = MessageSpecs.create(Fight.class);
    private final Messages messages = Messages.load("messages/context-test");

    @Test
    void aRoleFillsEveryPropertyOfItsContext() {
        assertEquals(
                "Ada hit Bo for 3.",
                messages.format(Locale.ENGLISH, fight.hit(new PlayerContext("Ada"), new PlayerContext("Bo"), 3)));
    }

    @Test
    void serverAndSeasonAreInEveryMessage() {
        Contexts.server("smp");
        assertEquals(
                "Season 2 on smp: 40%",
                messages.format(Locale.ENGLISH, fight.bar().progress(40)));
    }

    @Test
    void aContextValueIsEscapedLikeAnyOther() {
        final Component line = MessageRenderer.of(messages)
                .format(Locale.ENGLISH, fight.hit(new PlayerContext("<red>Ada"), new PlayerContext("Bo"), 1));
        assertEquals("<red>Ada hit Bo for 1.", plain(line));
    }

    @Test
    void theSchemaNamesRolesTheirTypesFormatAndDisplay() {
        final List<MessageSchema.Entry> entries = MessageSchema.entries(Fight.class);
        final MessageSchema.Entry hit = entries.get(0);
        assertEquals(
                List.of(
                        new MessageSchema.Arg("attacker", false, "player"),
                        new MessageSchema.Arg("victim", false, "player"),
                        new MessageSchema.Arg("damage", false, null)),
                hit.args());
        assertEquals(Display.TITLE, hit.shown());
        assertEquals(TextFormat.MINIMESSAGE, hit.format());
        assertEquals(Display.BOSS_BAR, entries.get(1).shown());
        assertEquals(Display.DISCORD_BUTTON, entries.get(2).shown());
        assertEquals(TextFormat.PLAIN, entries.get(2).format());

        final String json = MessageSchema.json(Fight.class);
        assertTrue(json.contains("{\"name\": \"attacker\", \"component\": false, \"context\": \"player\"}"), json);
        assertTrue(json.contains("\"format\": \"MINIMESSAGE\", \"shown\": \"TITLE\""), json);
        assertTrue(json.contains("\"player\": {\"name\": \"Player\", \"properties\": [\"name\"]}"), json);
        assertTrue(
                json.contains("\"globals\": [{\"name\": \"server\", \"context\": \"service\"}, "
                        + "{\"name\": \"season\", \"context\": \"season\"}]"),
                json);
    }

    @Test
    void aSpecWithRolesThatMatchesItsBundleHasNoProblems() {
        assertEquals(List.of(), MessageSpecCheck.problems(Fight.class));
    }

    @MessageSpec("context-test")
    interface Drifted {

        @Name("Hit")
        MessageRef hit(
                @Arg("attacker") PlayerContext attacker,
                @Arg("target") PlayerContext target,
                @Arg("damage") Object damage);

        @Name("Bar")
        Drifted.Bar bar();

        interface Bar {

            @Name("Progress")
            MessageRef progress(@Arg("percent") Object percent);

            @Name("Button")
            MessageRef button(@Arg("team") PlayerContext team);
        }
    }

    @Test
    void aPropertyTheRoleDoesNotHaveAndAnUnusedRoleAreNamed() {
        final List<String> problems = MessageSpecCheck.problems(Drifted.class);
        assertTrue(
                problems.contains("hit (en): the text names {victim.name}, which nothing declares"),
                problems::toString);
        assertTrue(problems.contains("hit (en): the role target is never used"), problems::toString);
        assertEquals(2 * 2, problems.size(), problems::toString);
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
