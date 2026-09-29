package eu.nordtal.s2.commands.access;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;
import java.util.List;

/** {@code /access reload}: re-reads the bot's wording and names override keys that match nothing. */
public final class ReloadBotMessages implements NordtalCommand<AccessEffects> {

    @Override
    public Declaration declaration() {
        return AccessCommands.RELOAD_MESSAGES;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final AccessEffects effects) {
        effects.async(() -> {
            if (!effects.reloadMessages()) {
                user.reply(MESSAGES.access().messages().reloadFailed(), Feedback.REFUSED, Tone.BAD);
                return;
            }
            final List<String> unknown = effects.unknownOverrideKeys();
            if (unknown.isEmpty()) {
                user.reply(MESSAGES.access().messages().reloaded(), Feedback.SMALL_SUCCESS, Tone.GOOD);
            } else {
                user.reply(
                        MESSAGES.access().messages().reloadedWithUnknown(String.join(", ", unknown)),
                        // The reload worked but some override keys name nothing, so WARN rather than BAD.
                        Feedback.REFUSED,
                        Tone.WARN);
            }
        });
    }
}
