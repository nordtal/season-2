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
 * {@code /smp milestone unlock <key>}: unlocks the active milestone by hand, with no way back.
 *
 * Any other key is refused: unlocking out of order would leave two milestones active.
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
                final Optional<String> active = effects.activeMilestone();
                if (active.isEmpty()) {
                    user.reply(MESSAGES.smp().admin().nothingToUnlock(), Feedback.REFUSED, Tone.WARN);
                    return;
                }
                if (!active.get().equals(key)) {
                    user.reply(
                            MESSAGES.smp().admin().milestoneNotActive(key, new MilestoneContext(active.get())),
                            Feedback.REFUSED,
                            Tone.BAD);
                    return;
                }
                effects.unlockMilestone(key);
            } catch (final RuntimeException failure) {
                effects.warn("/smp milestone unlock " + key + " failed", failure);
                user.reply(MESSAGES.smp().admin().readFailed(), Feedback.REFUSED, Tone.BAD);
                return;
            }
            user.reply(MESSAGES.smp().admin().milestoneUnlocked(key), Feedback.BIG_SUCCESS, Tone.GOOD);
        });
    }
}
