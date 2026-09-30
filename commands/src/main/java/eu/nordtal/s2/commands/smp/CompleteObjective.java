package eu.nordtal.s2.commands.smp;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.messages.Tone;
import eu.nordtal.s2.messages.context.MilestoneContext;
import eu.nordtal.s2.messages.feedback.Feedback;
import java.util.Optional;

/**
 * {@code /smp objective complete <key>}: closes one objective of the active milestone by hand.
 *
 * Pays {@code pot x (reached / target)}, never the full pot, so the hatch is never worth more than the work.
 */
public final class CompleteObjective implements NordtalCommand<SmpEffects> {

    @Override
    public Declaration declaration() {
        return SmpCommands.COMPLETE_OBJECTIVE;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final SmpEffects effects) {
        final String key = values.string("key");
        effects.async(() -> {
            final Optional<String> active;
            try {
                active = effects.activeMilestone();
            } catch (final RuntimeException failure) {
                effects.warn("/smp objective complete could not read the active milestone", failure);
                user.reply(MESSAGES.smp().admin().readFailed(), Feedback.REFUSED, Tone.BAD);
                return;
            }
            if (active.isEmpty()) {
                user.reply(MESSAGES.smp().admin().noActiveMilestone(), Feedback.REFUSED, Tone.WARN);
                return;
            }
            // Guarded like the read above, so a throw cannot escape the async runnable unanswered.
            try {
                if (!effects.hasObjective(active.get(), key)) {
                    user.reply(MESSAGES.smp().admin().noSuchObjective(), Feedback.REFUSED, Tone.BAD);
                    return;
                }
                effects.completeObjective(active.get(), key);
            } catch (final RuntimeException failure) {
                effects.warn("/smp objective complete could not close '" + key + "'", failure);
                user.reply(MESSAGES.smp().admin().readFailed(), Feedback.REFUSED, Tone.BAD);
                return;
            }
            user.reply(
                    MESSAGES.smp().admin().objectiveCompleted(key, new MilestoneContext(active.get())),
                    Feedback.BIG_SUCCESS,
                    Tone.GOOD);
        });
    }
}
