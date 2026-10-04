package eu.nordtal.s2.steward.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.nordtal.s2.internalapi.agent.MessageArg;
import eu.nordtal.s2.internalapi.agent.MessageEntry;
import eu.nordtal.s2.messages.text.MessageCheck;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** An admin's save runs the one validator over what the jar's schema declares. */
class OverrideCheckTest {

    private static final MessageEntry WON = entry(
            "Duel won",
            "MINIMESSAGE",
            "CHAT",
            List.of(
                    new MessageArg("winner.name", "name", "player", false, "Alex", false),
                    new MessageArg("winner.self", "choice", "player", false, "false", false),
                    new MessageArg("spins", "number", null, false, "3", false),
                    new MessageArg("server.name", "text", "service", true, "smp", false),
                    new MessageArg("rematch", null, null, false, null, true)));

    private static List<String> errors(final MessageEntry entry, final String text) {
        return OverrideCheck.problems(entry, text).stream()
                .filter(MessageCheck.Problem::error)
                .map(problem -> MessageCheck.english(problem.text()))
                .toList();
    }

    private static List<String> warnings(final MessageEntry entry, final String text) {
        return OverrideCheck.problems(entry, text).stream()
                .filter(problem -> !problem.error())
                .map(problem -> MessageCheck.english(problem.text()))
                .toList();
    }

    @Test
    void whatTheSchemaDeclaresPassesAndAColourIsAnOverridesToChoose() {
        assertEquals(
                List.of(),
                errors(
                        WON,
                        "<#ff8800>{winner.name}</#ff8800> won {spins} on {server.name}."
                                + " <action:rematch>[Again]</action>"));
    }

    @Test
    void anUndeclaredPlaceholderOrActionIsRefused() {
        assertTrue(
                errors(WON, "{winner.nope} {winner.name} {spins}").getFirst().startsWith("{winner.nope} is nothing"));
        assertTrue(errors(WON, "{winner.name} {spins} <action:quit>x</action>")
                .getFirst()
                .contains("no action"));
    }

    @Test
    void aValueTheTextNoLongerShowsIsAWarningAndNotARefusal() {
        assertEquals(List.of(), errors(WON, "{winner.name} won."));
        assertEquals(
                List.of("the text never shows spins, which the message is given"), warnings(WON, "{winner.name} won."));
    }

    @Test
    void aTextTooLongForWhereItIsShownIsRefused() {
        final MessageEntry button = entry(
                "Button",
                "PLAIN",
                "DISCORD_BUTTON",
                List.of(new MessageArg("days", "number", null, false, "30", false)));
        assertEquals(List.of(), errors(button, "Buy {days} days"));
        assertTrue(
                errors(button, "Buy {days} days " + "x".repeat(80)).getFirst().contains("80 fit"));
    }

    @Test
    void aKeyWithoutASchemaIsNeverChecked() {
        assertEquals(List.of(), OverrideCheck.problems(entry(null, null, null, List.of()), "Welcome {player}"));
    }

    private static MessageEntry entry(
            final @Nullable String name,
            final @Nullable String format,
            final @Nullable String shown,
            final List<MessageArg> args) {
        return new MessageEntry(
                "key",
                "smp",
                "text",
                null,
                List.of("text"),
                List.of(),
                null,
                null,
                true,
                name,
                null,
                args,
                List.of(),
                format,
                shown);
    }
}
