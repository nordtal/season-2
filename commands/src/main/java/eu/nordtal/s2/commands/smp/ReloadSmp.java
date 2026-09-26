package eu.nordtal.s2.commands.smp;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;

/**
 * {@code /smp reload} - re-read the two reloadable files and the message bundles.
 *
 * Not confirmed and not reversible in the usual sense: re-reading a file changes nothing that was
 * not already on disk, and the interesting failure is a file that refuses to load, which the console
 * reports on its own. {@code config.yml} is deliberately not among them - the plugin binds worlds,
 * borders and coordinates once at enable and would not notice any of them changing.
 */
public final class ReloadSmp implements NordtalCommand<SmpEffects> {

    @Override
    public Declaration declaration() {
        return SmpCommands.RELOAD;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final SmpEffects effects) {
        effects.async(() -> {
            final java.util.List<String> refused;
            try {
                refused = effects.reload();
            } catch (final RuntimeException failure) {
                effects.warn("/smp reload failed", failure);
                user.reply(MESSAGES.smp().admin().reloadFailed(), Feedback.REFUSED, Tone.BAD);
                return;
            }
            if (!refused.isEmpty()) {
                // Named, not summarised. The person running this is editing milestones.yml on a running season.
                user.reply(MESSAGES.smp().admin().trackRefused(String.join("\n", refused)), Feedback.REFUSED, Tone.BAD);
                return;
            }
            user.reply(MESSAGES.smp().admin().reloaded(), Feedback.SMALL_SUCCESS, Tone.GOOD);
        });
    }
}
