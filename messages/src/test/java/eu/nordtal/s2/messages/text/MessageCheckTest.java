package eu.nordtal.s2.messages.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.messages.MessageRef;
import eu.nordtal.s2.messages.text.MessageCheck.Mode;
import eu.nordtal.s2.messages.value.Kind;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The one validator: what the build, an admin's save and a process's load refuse. */
class MessageCheckTest {

    /** A chat line offering a player role, a count, a reason and an action. */
    private static final Declaration CHAT = new Declaration(
            Map.of(
                    "winner.name",
                    Kind.DISPLAY_NAME,
                    "winner.self",
                    Kind.CHOICE,
                    "n",
                    Kind.NUMBER,
                    "reason",
                    Kind.TEXT,
                    "open",
                    Kind.CHOICE),
            Set.of("winner", "n"),
            Set.of("accept"),
            true,
            0,
            Map.of("winner.name", "Alex", "n", "3", "reason", "Nordtal"));

    private static List<String> packaged(final String text) {
        return MessageCheck.errors(text, CHAT, Mode.PACKAGED);
    }

    private static List<String> override(final String text) {
        return MessageCheck.errors(text, CHAT, Mode.OVERRIDE);
    }

    private static void refused(final List<String> errors, final String part) {
        assertTrue(errors.stream().anyMatch(error -> error.contains(part)), () -> part + " not in " + errors);
    }

    @Test
    void aWellFormedTextPasses() {
        assertEquals(
                List.of(),
                packaged("<good>{winner.name}</good> has {n, plural, one {# win} other {# wins}}"
                        + " <hover:show_text:'{reason}'>here</hover> <action:accept>[Accept]</action>"));
    }

    @Test
    void aProblemIsAMessageOfTheCheckBundleWithItsValues() {
        final MessageRef problem = MessageCheck.check("{loser.name} {winner.name} {n}", CHAT, Mode.PACKAGED)
                .getFirst()
                .text();

        assertEquals("check.value.unknown", problem.key());
        assertEquals("loser.name", problem.args().get("name"));
        assertEquals(
                "{loser.name} is nothing this message offers; it offers n, open, reason, winner.name and winner.self",
                MessageCheck.english(problem));
    }

    @Test
    void anUnreadableTextIsAMessageThatNamesTheCharacter() {
        final MessageSyntaxException refused =
                assertThrows(MessageSyntaxException.class, () -> MessageText.parse("Hi {name", true));

        assertEquals("check.syntax.comma-after-name", refused.reason().key());
        assertEquals(8, refused.position());
        assertEquals("expected , after the placeholder's name (at character 9)", refused.getMessage());
    }

    @Test
    void anUnknownRoleOrAttributeIsRefused() {
        refused(packaged("{loser.name} {winner.name} {n}"), "{loser.name} is nothing this message offers");
        refused(packaged("{winner.colour} {winner.name} {n}"), "{winner.colour} is nothing");
    }

    @Test
    void aRoleThePackagedTextNeverShowsIsRefusedAndAnOverrideIsOnlyWarned() {
        refused(packaged("{winner.name} won"), "never shows n");
        assertEquals(List.of(), override("{winner.name} won"));
        assertTrue(MessageCheck.check("{winner.name} won", CHAT, Mode.OVERRIDE).stream()
                .anyMatch(problem ->
                        !problem.error() && MessageCheck.english(problem.text()).contains("never shows n")));
    }

    @Test
    void anUnclosedOrUnknownTagIsRefused() {
        refused(packaged("<good>{winner.name} {n}"), "<good> is never closed");
        refused(packaged("<shout>{winner.name} {n}</shout>"), "<shout> is no tag");
        refused(packaged("<good>{winner.name} {n}</bad>"), "</bad> closes");
    }

    @Test
    void aColourIsAnOverridesAndNeverAPackagedTexts() {
        refused(packaged("<#ff0000>{winner.name} {n}</#ff0000>"), "names colours only by tone");
        refused(packaged("<red>{winner.name} {n}</red>"), "names colours only by tone");
        assertEquals(List.of(), override("<gradient:#ff0000:#00ff00>{winner.name} {n}</gradient>"));
    }

    @Test
    void aValueInATagArgumentIsRefusedExceptInAHoverTextOrAClickTarget() {
        refused(packaged("<font:'{reason}'>{winner.name} {n}</font>"), "where no value may");
        refused(packaged("<hover:show_text:{reason}>{winner.name} {n}</hover>"), "without quotes");
        assertEquals(List.of(), packaged("<click:suggest_command:'/msg {winner.name}'>{winner.name} {n}</click>"));
    }

    @Test
    void anActionMustBeOneTheCodeOffers() {
        refused(packaged("<action:deny>no</action> {winner.name} {n}"), "names no action this message offers");
    }

    @Test
    void aKindStyleOrChoiceThatDoesNotFitIsRefused() {
        refused(packaged("{winner.name} {n, duration}"), "{n} is a number, not a duration");
        refused(packaged("{winner.name} {n, number, fancy}"), "has no style fancy");
        refused(packaged("{winner.name} {n, select, other {x}}"), "chooses on a number");
        refused(packaged("{winner.name} {n, plural, single {x} other {y}}"), "no plural category");
        refused(packaged("{winner.name} {n} {open, plural, other {x}}"), "chooses on a choice");
    }

    @Test
    void aSyntaxErrorIsRefused() {
        refused(packaged("{winner.name} {n"), "");
        refused(packaged("{winner.name} {n} }"), "closes nothing");
        refused(packaged("{winner.name} {n, plural, one {x}}"), "other");
    }

    @Test
    void aTextLongerThanItsDisplayWithTheExamplesFilledInIsRefused() {
        final Declaration button =
                new Declaration(Map.of("n", Kind.NUMBER), Set.of("n"), Set.of(), false, 12, Map.of("n", "300"));
        assertEquals(List.of(), MessageCheck.errors("Buy {n} now", button, Mode.OVERRIDE));
        refused(MessageCheck.errors("Buy {n} right now", button, Mode.OVERRIDE), "characters long");
    }
}
