package eu.nordtal.s2.commands.access;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;
import java.util.List;

/**
 * {@code /access reload} - the bot's own wording.
 *
 * The one place in the network where a reload reports what it found rather than only whether it
 * worked: an override key no bundle declares is stored and never used, which looks exactly like an
 * override that works. Saying so at the moment somebody edits the file is the only time it is
 * useful.
 */
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
                        // The reload worked; some override keys name nothing. WARN rather than BAD.
                        Feedback.REFUSED,
                        Tone.WARN);
            }
        });
    }
}
