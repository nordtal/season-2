package eu.nordtal.s2.commands.smp;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.messages.Tone;
import eu.nordtal.s2.messages.feedback.Feedback;

/**
 * {@code /smp reload}: re-reads the two reloadable files and the message bundles.
 *
 * Never {@code config.yml}: the plugin binds worlds, borders and coordinates once at enable.
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
                // Named, not summarised: the person running this is editing milestones.yml on a running season.
                user.reply(MESSAGES.smp().admin().trackRefused(String.join("\n", refused)), Feedback.REFUSED, Tone.BAD);
                return;
            }
            user.reply(MESSAGES.smp().admin().reloaded(), Feedback.SMALL_SUCCESS, Tone.GOOD);
        });
    }
}
