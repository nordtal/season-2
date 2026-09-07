package eu.nordtal.s2.commands.update;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;

import java.util.Map;

/**
 * {@code /update cancel} - stop the countdown, for as long as one is running.
 *
 * <h2>"Too late" is an answer and not a failure</h2>
 * The cancel races an updater that may be claiming the very same row this millisecond, and the
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
                effects.cancel("Cancelled by " + user.name()).ifPresentOrElse(
                        cancelled -> user.reply("update.cancelled", Map.of(),
                                Feedback.SMALL_SUCCESS),
                        () -> user.reply("update.too-late", Map.of(), Feedback.REFUSED));
            } catch (final RuntimeException failure) {
                user.reply("update.write-failed", Map.of(), Feedback.REFUSED);
            }
        });
    }
}
