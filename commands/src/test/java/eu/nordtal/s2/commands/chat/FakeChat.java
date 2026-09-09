package eu.nordtal.s2.commands.chat;

import eu.nordtal.s2.commands.NordtalUser;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A proxy that delivers nothing and records everything. */
final class FakeChat implements ChatEffects {

    /** One entry per delivery attempt: the recipient (null for {@code /r}) and the text. */
    record Sent(UUID to, String text) {
    }

    final List<Sent> sent = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();

    /** What the next attempt answers. */
    Outcome answer = Outcome.SENT;

    /** When set, the effect throws instead of answering. */
    RuntimeException failure;

    @Override
    public void async(final Runnable work) {
        // Inline, so a test asserts after run() returns rather than waiting on a scheduler. The
        // real proxy hands this to Velocity's; the inbox hands it Runnable::run, which is this.
        work.run();
    }

    @Override
    public void warn(final String what, final Throwable cause) {
        warnings.add(what);
    }

    @Override
    public Outcome whisper(final NordtalUser from, final UUID to, final String text) {
        if (failure != null) {
            throw failure;
        }
        sent.add(new Sent(to, text));
        return answer;
    }

    @Override
    public Outcome replyToLast(final NordtalUser from, final String text) {
        if (failure != null) {
            throw failure;
        }
        sent.add(new Sent(null, text));
        return answer;
    }
}
