package eu.nordtal.s2.commands.network;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;

import java.util.Map;

/** {@code /network reload} - re-read the MOTD and every disconnect screen without dropping anybody. */
public final class ReloadNetwork implements NordtalCommand<NetworkEffects> {

    @Override
    public Declaration declaration() {
        return NetworkCommands.RELOAD;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final NetworkEffects effects) {
        effects.async(() -> {
            // The cue follows the result, not the command. Both of these answered a failed
            // reload with the success sound until 2026-09-05, which is the one thing an
            // operator hears without reading.
            final boolean reloaded = effects.reloadMessages();
            user.reply(reloaded ? "network.reloaded" : "network.reload-failed", Map.of(),
                    reloaded ? Feedback.SMALL_SUCCESS : Feedback.REFUSED);
        });
    }
}
