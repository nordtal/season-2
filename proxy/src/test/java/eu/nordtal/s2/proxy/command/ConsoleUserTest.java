package eu.nordtal.s2.proxy.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.nordtal.s2.commands.CommandMessages;
import eu.nordtal.s2.commands.Update;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Messages;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.message.context.ServiceContext;
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

    private static final Update UPDATE = CommandMessages.MESSAGES.update();

    @Test
    void theEmptyMapIsNotAnArgument() {
        final Spy spy = new Spy();
        new ConsoleUser(MESSAGES, spy).reply(UPDATE.asked());
        assertEquals(List.of("Asking Steward what is new."), spy.lines);
    }

    @Test
    void placeholdersAreSubstituted() {
        final Spy spy = new Spy();
        new ConsoleUser(MESSAGES, spy).reply(UPDATE.line().unchanged(new ServiceContext("limbo")));
        assertEquals(List.of("limbo: unchanged"), spy.lines);
    }

    @Test
    void severalPlaceholdersAllArrive() {
        // A flattening that loses the name and value pairing cannot produce this line by accident.
        final Spy spy = new Spy();
        new ConsoleUser(MESSAGES, spy).reply(UPDATE.change("velocity", "4.1.1", "4.2.0"));
        assertEquals(List.of("velocity 4.1.1 -> 4.2.0"), spy.lines);
    }

    @Test
    void theOverloadsWork() {
        // Default methods funnel Feedback and Tone down to reply(message); ReportUpdate calls the four-argument one.
        final Spy spy = new Spy();
        final ConsoleUser console = new ConsoleUser(MESSAGES, spy);
        console.reply(UPDATE.asked(), Feedback.SMALL_SUCCESS, Tone.GOOD);
        console.reply(UPDATE.line().unchanged(new ServiceContext("smp")), Tone.GOOD);
        assertEquals(List.of("Asking Steward what is new.", "smp: unchanged"), spy.lines);
    }
}
