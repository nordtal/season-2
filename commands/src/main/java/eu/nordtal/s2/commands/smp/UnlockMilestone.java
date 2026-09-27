package eu.nordtal.s2.commands.smp;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;

/**
 * {@code /smp milestone unlock <key>}: unlocks a whole milestone by hand, with no way back.
 *
 * Unlike {@link CompleteObjective}, an unknown key is not checked first: only the engine knows the whole track.
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
                user.reply(MESSAGES.smp().admin().readFailed(), Feedback.REFUSED, Tone.BAD);
                return;
            }
            user.reply(MESSAGES.smp().admin().milestoneUnlocked(key), Feedback.BIG_SUCCESS, Tone.GOOD);
        });
    }
}
