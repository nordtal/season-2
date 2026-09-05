package eu.nordtal.s2.commands.smp;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;

import java.util.Map;
import java.util.Optional;

/**
 * {@code /smp objective complete <key>} - close one objective of the active milestone by hand.
 *
 * <h2>Why the hatch exists</h2>
 * An objective can turn out to be impossible after it has been announced. Closing it pays out
 * {@code pot x (reached / target)} rather than the full pot, so using the hatch is never worth more
 * than doing the work - which is what keeps it from being a way to hand out aura.
 *
 * <h2>Two refusals, and both are worth having separately</h2>
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
                user.reply("smp.admin.read-failed", Map.of(), Feedback.REFUSED);
                return;
            }
            if (active.isEmpty()) {
                user.reply("smp.admin.no-active-milestone", Map.of(), Feedback.REFUSED);
                return;
            }
            // Guarded like the read above it. These two are the same kind of call and were the
            // only two in this class without a catch: a throw escaped the async runnable, so the
            // admin got no reply at all and nothing was logged, on a command that may or may not
            // have closed the objective.
            try {
                if (!effects.hasObjective(active.get(), key)) {
                    user.reply("smp.admin.no-such-objective", Map.of(), Feedback.REFUSED);
                    return;
                }
                effects.completeObjective(active.get(), key);
            } catch (final RuntimeException failure) {
                effects.warn("/smp objective complete could not close '" + key + "'", failure);
                user.reply("smp.admin.read-failed", Map.of(), Feedback.REFUSED);
                return;
            }
            user.reply("smp.admin.objective-completed",
                    Map.of("key", key, "milestone", active.get()), Feedback.BIG_SUCCESS);
        });
    }
}
