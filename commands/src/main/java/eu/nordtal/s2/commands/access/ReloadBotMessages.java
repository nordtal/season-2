package eu.nordtal.s2.commands.access;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;

import java.util.List;
import java.util.Map;

/**
 * {@code /access reload} - the bot's own wording.
 *
 * <p>The one place in the network where a reload reports what it found rather than only whether it
 * worked: an override key no bundle declares is stored and never used, which looks exactly like an
 * override that works. Saying so at the moment somebody edits the file is the only time it is
 * useful.</p>
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
                user.reply("access.messages.reload-failed", Map.of(), Feedback.REFUSED);
                return;
            }
            final List<String> unknown = effects.unknownOverrideKeys();
            if (unknown.isEmpty()) {
                user.reply("access.messages.reloaded", Map.of(), Feedback.SMALL_SUCCESS);
            } else {
                user.reply("access.messages.reloaded-with-unknown",
                        Map.of("keys", String.join(", ", unknown)), Feedback.REFUSED);
            }
        });
    }
}
