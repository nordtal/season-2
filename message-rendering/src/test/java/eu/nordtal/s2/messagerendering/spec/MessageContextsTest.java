package eu.nordtal.s2.messagerendering.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.common.id.PlayerId;
import eu.nordtal.s2.messagerendering.MessageRenderer;
import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.Palette;
import eu.nordtal.s2.messages.context.MessageEnvironment;
import eu.nordtal.s2.messages.context.PlayerContext;
import eu.nordtal.s2.messages.context.SeasonContext;
import eu.nordtal.s2.messages.context.TeamContext;
import eu.nordtal.s2.messages.spec.Arg;
import eu.nordtal.s2.messages.spec.Display;
import eu.nordtal.s2.messages.spec.Format;
import eu.nordtal.s2.messages.spec.MessageSchema;
import eu.nordtal.s2.messages.spec.MessageSpec;
import eu.nordtal.s2.messages.spec.MessageSpecCheck;
import eu.nordtal.s2.messages.spec.MessageSpecs;
import eu.nordtal.s2.messages.spec.Name;
import eu.nordtal.s2.messages.spec.Shown;
import eu.nordtal.s2.messages.spec.TextFormat;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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
                @Arg("damage") int damage);

        @Name("Bar")
        Fight.Bar bar();

        @Shown(Display.BOSS_BAR)
        interface Bar {

            @Name("Progress")
            MessageRef progress(@Arg("percent") int percent);

            @Name("Button")
            @Shown(Display.DISCORD_BUTTON)
            @Format(TextFormat.PLAIN)
            MessageRef button(@Arg("team") TeamContext team);
        }
    }

    private static final PlayerContext ADA = player("Ada");
    private static final PlayerContext BO = player("Bo");

    private final Fight fight = MessageSpecs.create(Fight.class);
    private final Messages messages = Messages.load("messages/context-test");

    @Test
    void aRoleFillsEveryPropertyOfItsContext() {
        assertEquals("Ada hit Bo for 3.", messages.format(Locale.ENGLISH, fight.hit(ADA, BO, 3)));
    }

    @Test
    void serverAndSeasonAreInEveryMessage() {
        assertEquals(
                "Season 2 on smp: 40%",
                messages.within(MessageEnvironment.of(
                                "smp", new SeasonContext(2, "Season 2"), ZoneId.of("UTC"), Palette.DEFAULTS))
                        .format(Locale.ENGLISH, fight.bar().progress(40)));
    }

    @Test
    void withoutAnEnvironmentTheGlobalRolesReadAsTheirReplacementWords() {
        assertEquals(
                "Season something on something: 40%",
                messages.format(Locale.ENGLISH, fight.bar().progress(40)));
    }

    @Test
    void aContextValueIsEscapedLikeAnyOther() {
        final Component line =
                MessageRenderer.of(messages).format(Locale.ENGLISH, fight.hit(player("<red>Ada"), BO, 1));
        assertEquals("<red>Ada hit Bo for 1.", plain(line));
    }

    @Test
    void theSchemaNamesRolesTheirTypesFormatAndDisplay() {
        final List<MessageSchema.Entry> entries = MessageSchema.entries(Fight.class);
        final MessageSchema.Entry hit = entries.get(0);
        assertEquals(
                List.of(
                        new MessageSchema.Arg("attacker", null, "player", null, false),
                        new MessageSchema.Arg("victim", null, "player", null, false),
                        new MessageSchema.Arg("damage", "number", null, "3", false)),
                hit.args());
        assertEquals(Display.TITLE, hit.shown());
        assertEquals(TextFormat.MINIMESSAGE, hit.format());
        assertEquals(Display.BOSS_BAR, entries.get(1).shown());
        assertEquals(Display.DISCORD_BUTTON, entries.get(2).shown());
        assertEquals(TextFormat.PLAIN, entries.get(2).format());

        final String json = MessageSchema.json(Fight.class);
        assertTrue(json.contains("{\"name\":\"attacker\",\"context\":\"player\",\"action\":false}"), json);
        assertTrue(json.contains("\"format\":\"MINIMESSAGE\",\"shown\":\"TITLE\""), json);
        assertTrue(
                json.contains("\"player\":{\"name\":\"Player\",\"attributes\":[{\"name\":\"name\",\"kind\":\"name\","
                        + "\"example\":\"Alex\"},{\"name\":\"self\",\"kind\":\"choice\",\"example\":\"false\"}]}"),
                json);
        assertTrue(
                json.contains("\"globals\":[{\"name\":\"server\",\"context\":\"service\"},"
                        + "{\"name\":\"season\",\"context\":\"season\"},{\"name\":\"network\",\"context\":\"network\"},"
                        + "{\"name\":\"viewer\",\"context\":\"player\"}]"),
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
                @Arg("damage") int damage);

        @Name("Bar")
        Drifted.Bar bar();

        interface Bar {

            @Name("Progress")
            MessageRef progress(@Arg("percent") int percent);

            @Name("Button")
            MessageRef button(@Arg("team") PlayerContext team);
        }
    }

    @Test
    void aPropertyTheRoleDoesNotHaveAndAnUnusedRoleAreNamed() {
        final List<String> problems = MessageSpecCheck.problems(Drifted.class);
        assertTrue(
                problems.stream()
                        .anyMatch(problem ->
                                problem.startsWith("hit (en): {victim.name} is nothing this message offers")),
                problems::toString);
        assertTrue(
                problems.contains("hit (en): the text never shows target, which the message is given"),
                problems::toString);
        assertEquals(2 * 2, problems.size(), problems::toString);
    }

    private static PlayerContext player(final String name) {
        return PlayerContext.of(PlayerId.of(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8))), name);
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
