package eu.nordtal.s2.commands.network;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;

/** {@code /network reload} - re-read the MOTD and every disconnect screen without dropping anybody. */
public final class ReloadNetwork implements NordtalCommand<NetworkEffects> {

    @Override
    public Declaration declaration() {
        return NetworkCommands.RELOAD;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final NetworkEffects effects) {
        effects.async(() -> {
            // The cue follows the result, not the command: the sound an operator hears without reading must not lie.
            final boolean reloaded = effects.reloadMessages();
            user.reply(
                    reloaded
                            ? MESSAGES.network().reloaded()
                            : MESSAGES.network().reloadFailed(),
                    reloaded ? Feedback.SMALL_SUCCESS : Feedback.REFUSED,
                    reloaded ? Tone.GOOD : Tone.BAD);
        });
    }
}
