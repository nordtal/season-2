package eu.nordtal.s2.commands.smp;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;

import java.util.Map;

/**
 * {@code /smp reload} - re-read the two reloadable files and the message bundles.
 *
 * <p>Not confirmed and not reversible in the usual sense: re-reading a file changes nothing that was
 * not already on disk, and the interesting failure is a file that refuses to load, which the console
 * reports on its own. {@code config.yml} is deliberately not among them - the plugin binds worlds,
 * borders and coordinates once at enable and would not notice any of them changing.</p>
 */
public final class ReloadSmp implements NordtalCommand<SmpEffects> {

    @Override
    public Declaration declaration() {
        return SmpCommands.RELOAD;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final SmpEffects effects) {
        effects.async(() -> {
            try {
                effects.reload();
            } catch (final RuntimeException failure) {
                effects.warn("/smp reload failed", failure);
                user.reply("smp.admin.reload-failed", Map.of(), Feedback.REFUSED);
                return;
            }
            user.reply("smp.admin.reloaded", Map.of(), Feedback.SMALL_SUCCESS);
        });
    }
}
