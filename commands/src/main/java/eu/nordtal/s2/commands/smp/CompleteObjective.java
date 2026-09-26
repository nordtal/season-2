package eu.nordtal.s2.commands.smp;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.message.context.MilestoneContext;
import java.util.Optional;

/**
 * {@code /smp objective complete <key>} - close one objective of the active milestone by hand.
 *
 * An objective can turn out to be impossible after it has been announced. Closing it pays out
 * {@code pot x (reached / target)} rather than the full pot, so using the hatch is never worth more
 * than doing the work - which is what keeps it from being a way to hand out aura.
 *
 * Two refusals, and both are worth having separately:
 * "No milestone is active" and "the active milestone has no objective by that key" are different
 * mistakes: the first means the track has not started or is finished, the second is a typo. Folding
 * them into one sentence would leave an admin re-reading the milestone file for a key that is in it.
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
            // Guarded like the read above it, so a throw here cannot escape the async runnable and leave the admin.
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
