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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The proxy console can answer at all.
 *
 * <h2>Why this test exists, and it is not a hypothetical</h2>
 * {@code mc update} on {@code nordtal-s2-proxy-1} threw
 * {@code IllegalArgumentException: parameters must alternate name and value, got 1} on 2026-09-15,
 * from {@code MessageRenderer.format} by way of this class. {@code format} takes
 * {@code (Locale, String, Object...)} and {@link ConsoleUser} handed it the placeholder
 * {@code Map} as a single vararg - which compiles, because a {@code Map} is an {@code Object}, and
 * which is wrong for every call including one with an empty map. The proxy console could therefore
 * answer <em>no</em> command at all.
 *
 * <p>Its two siblings, {@code PaperUser} and - in this very package - {@link VelocityUser}, each
 * flatten the map into alternating name and value first. This class was the third and the only one
 * without it.</p>
 *
 * <h2>Why the empty map is its own case</h2>
 * Because it is the one that made the bug reach everything. {@code update.asked} carries no
 * placeholder at all, and it was still the line that threw - so a test that only checked
 * substitution would have passed on a console that could not say "Asking Steward what is new."
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
    @DisplayName("a reply with no placeholders arrives, which is the case that was broken")
    void theEmptyMapIsNotAnArgument() {
        final Spy spy = new Spy();
        new ConsoleUser(MESSAGES, spy).reply(UPDATE.asked());
        assertEquals(List.of("Asking Steward what is new."), spy.lines);
    }

    @Test
    @DisplayName("a reply substitutes its placeholders instead of printing the braces")
    void placeholdersAreSubstituted() {
        final Spy spy = new Spy();
        new ConsoleUser(MESSAGES, spy).reply(UPDATE.line().unchanged(new ServiceContext("limbo")));
        assertEquals(List.of("limbo: unchanged"), spy.lines);
    }

    @Test
    @DisplayName("three placeholders all arrive, so the flattening cannot be accidentally paired")
    void severalPlaceholdersAllArrive() {
        // One name/value pair happens to come out right under several wrong implementations - drop
        // the name, swap name and value, keep only the first entry - and it is the case the two
        // tests above cover. `update.change` is `{artefact} {from} -> {to}`, so a flattening that
        // loses the pairing cannot produce this line by accident. The entry order of `Map.of` is
        // unspecified on purpose here: substitution is by name, so the output must not depend on it.
        final Spy spy = new Spy();
        new ConsoleUser(MESSAGES, spy).reply(UPDATE.change("velocity", "4.1.1", "4.2.0"));
        assertEquals(List.of("velocity 4.1.1 -> 4.2.0"), spy.lines);
    }

    @Test
    @DisplayName("the overloads every command actually calls reach the same place")
    void theOverloadsWork() {
        // NordtalUser's default methods funnel Feedback and Tone down to reply(message).
        // ReportUpdate calls the four-argument one, and that is the call that threw in production.
        final Spy spy = new Spy();
        final ConsoleUser console = new ConsoleUser(MESSAGES, spy);
        console.reply(UPDATE.asked(), Feedback.SMALL_SUCCESS, Tone.GOOD);
        console.reply(UPDATE.line().unchanged(new ServiceContext("smp")), Tone.GOOD);
        assertEquals(List.of("Asking Steward what is new.", "smp: unchanged"), spy.lines);
    }
}
