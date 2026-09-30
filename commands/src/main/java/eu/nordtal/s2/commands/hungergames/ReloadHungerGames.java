package eu.nordtal.s2.commands.hungergames;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.messages.Tone;
import eu.nordtal.s2.messages.feedback.Feedback;

/**
 * {@code /hg reload}: the wording and the sounds, never {@code config.yml}.
 *
 * Its border schedule is a running clock players flee from; the sounds reload first, independently of the messages.
 */
public final class ReloadHungerGames implements NordtalCommand<HungerGamesEffects> {

    @Override
    public Declaration declaration() {
        return HungerGamesCommands.RELOAD;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final HungerGamesEffects effects) {
        effects.async(() -> {
            final boolean sounds = effects.reloadSounds();
            final boolean messages = effects.reloadMessages();

            if (sounds && messages) {
                // No sound: the admin is already reading the confirmation.
                user.reply(MESSAGES.hg().admin().reloaded(), Tone.GOOD);
            } else {
                user.reply(MESSAGES.hg().admin().reloadFailed(), Feedback.REFUSED, Tone.BAD);
            }
        });
    }
}
