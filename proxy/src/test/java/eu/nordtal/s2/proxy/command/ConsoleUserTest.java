package eu.nordtal.s2.proxy.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.commands.Command;
import eu.nordtal.s2.commands.CommandMessages;
import eu.nordtal.s2.messages.Messages;
import eu.nordtal.s2.messages.Tone;
import eu.nordtal.s2.messages.feedback.Feedback;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/**
 * The proxy console can answer at all.
 *
 * The placeholder map must be flattened into alternating name and value, even when it is empty.
 */
class ConsoleUserTest {

    private static final Messages MESSAGES =
            Messages.load(ConsoleUserTest.class.getClassLoader(), "messages/commands", Locale.ENGLISH, Locale.GERMAN);

    /** Collects what was sent, the way the container log would receive it. */
    private static final class Spy implements Audience {
        private final List<String> lines = new ArrayList<>();

        @Override
        public void sendMessage(final Component message) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(message));
        }
    }

    private static final Command COMMAND = CommandMessages.MESSAGES.command();

    @Test
    void theEmptyMapIsNotAnArgument() {
        final Spy spy = new Spy();
        new ConsoleUser(MESSAGES, spy).reply(COMMAND.cancelled());
        assertEquals(List.of("Cancelled. Nothing was changed."), spy.lines);
    }

    @Test
    void placeholdersAreSubstituted() {
        final Spy spy = new Spy();
        new ConsoleUser(MESSAGES, spy).reply(COMMAND.help().usage("/network reload"));
        assertEquals(List.of("Usage: /network reload"), spy.lines);
    }

    @Test
    void severalPlaceholdersAllArrive() {
        // A flattening that loses the name and value pairing cannot produce this line by accident.
        final Spy spy = new Spy();
        new ConsoleUser(MESSAGES, spy).reply(COMMAND.help().line("/network reload", "reloads the messages"));
        assertEquals(List.of("  /network reload - reloads the messages"), spy.lines);
    }

    @Test
    void theOverloadsWork() {
        // Default methods funnel Feedback and Tone down to reply(message).
        final Spy spy = new Spy();
        final ConsoleUser console = new ConsoleUser(MESSAGES, spy);
        console.reply(COMMAND.cancelled(), Feedback.SMALL_SUCCESS, Tone.GOOD);
        console.reply(COMMAND.help().usage("/smp reload"), Tone.GOOD);
        assertEquals(List.of("Cancelled. Nothing was changed.", "Usage: /smp reload"), spy.lines);
    }
}
