package eu.nordtal.s2.commands.smp;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;

import java.util.Map;

/**
 * {@code /smp farmreset now} - delete the farm world folder and regenerate it.
 *
 * <h2>The answer comes before the work, on purpose</h2>
 * Regenerating a world takes long enough that a reply afterwards would arrive after the server had
 * visibly stalled, and on the remote path it would arrive after the asker had given up. So the
 * sentence is "resetting now", said first, and the work follows. That is also why this command's
 * confirmation is not optional: there is no moment after this line at which it can be stopped.
 */
public final class ResetFarmWorld implements NordtalCommand<SmpEffects> {

    @Override
    public Declaration declaration() {
        return SmpCommands.FARM_RESET;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final SmpEffects effects) {
        user.reply("smp.admin.farmreset", Map.of(), Feedback.NETWORK_EVENT);
        effects.async(() -> {
            try {
                effects.resetFarmWorld();
            } catch (final RuntimeException failure) {
                effects.warn("/smp farmreset now failed", failure);
                user.reply("smp.admin.farmreset-failed", Map.of(), Feedback.REFUSED);
            }
        });
    }
}
