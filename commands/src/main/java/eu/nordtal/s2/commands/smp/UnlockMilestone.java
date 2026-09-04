package eu.nordtal.s2.commands.smp;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;

import java.util.Map;

/**
 * {@code /smp milestone unlock <key>} - unlock a whole milestone by hand.
 *
 * <p>The blunt version of {@link CompleteObjective}, and the one that cannot be undone in any useful
 * sense: it advances the season track and pays aura out to everybody who qualified. There is no
 * "relock", because a milestone that was announced as reached and then taken back is worse than one
 * that was reached early.</p>
 *
 * <p>Unlike completing an objective, an unknown key is not checked first. The engine is the only
 * thing that knows the whole track - including milestones that are not active - and asking it twice
 * would be two reads for a command an admin runs a handful of times a season.</p>
 */
public final class UnlockMilestone implements NordtalCommand<SmpEffects> {

    @Override
    public Declaration declaration() {
        return SmpCommands.UNLOCK_MILESTONE;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final SmpEffects effects) {
        final String key = values.string("key");
        effects.async(() -> {
            try {
                effects.unlockMilestone(key);
            } catch (final RuntimeException failure) {
                effects.warn("/smp milestone unlock " + key + " failed", failure);
                user.reply("smp.admin.read-failed", Map.of(), Feedback.REFUSED);
                return;
            }
            user.reply("smp.admin.milestone-unlocked", Map.of("key", key), Feedback.BIG_SUCCESS);
        });
    }
}
