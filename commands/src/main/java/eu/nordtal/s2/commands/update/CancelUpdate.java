package eu.nordtal.s2.commands.update;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;

/**
 * {@code /update cancel} - stop the countdown, for as long as one is running.
 *
 * <h2>"Too late" is an answer and not a failure</h2>
 * The cancel races a steward-worker that may be claiming the very same row this millisecond, and the
 * statement behind it is guarded rather than read-then-written for exactly that reason. An empty
 * answer means the run has already begun - which is the sentence the asker needs, not an error.
 */
public final class CancelUpdate implements NordtalCommand<UpdateEffects> {

    @Override
    public Declaration declaration() {
        return UpdateCommands.CANCEL;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final UpdateEffects effects) {
        effects.async(() -> {
            try {
                effects.cancel("Cancelled by " + user.name())
                        .ifPresentOrElse(
                                cancelled ->
                                        user.reply(MESSAGES.update().cancelled(), Feedback.SMALL_SUCCESS, Tone.GOOD),
                                // WARN: the run is already past the point of calling it off, which is not
                                // a failure of anything and is what somebody has to be told apart from one.
                                () -> user.reply(MESSAGES.update().tooLate(), Feedback.REFUSED, Tone.WARN));
            } catch (final RuntimeException failure) {
                user.reply(MESSAGES.update().writeFailed(), Feedback.REFUSED, Tone.BAD);
            }
        });
    }
}
