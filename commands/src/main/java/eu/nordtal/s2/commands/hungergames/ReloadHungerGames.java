package eu.nordtal.s2.commands.hungergames;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;

import java.util.Map;

/**
 * {@code /hg reload} - the wording and the sounds, and nothing else.
 *
 * <p>{@code config.yml} holds the border schedule and the loot timings, and a game is a running
 * clock: re-reading those mid-match would move a shrink that players are already running from. A
 * typo in a message is worth fixing during a game and so is a chime that turns out to be unbearable
 * with twenty people on towers; a border parameter is not.</p>
 *
 * <p>Two independent attempts, in that order: the sounds first because they are the cheapest thing
 * to get wrong and the only one an operator is expected to be iterating on while somebody waits to
 * hear the result. A broken {@code sounds.yml} must not stop a corrected message from arriving.</p>
 */
public final class ReloadHungerGames implements NordtalCommand<HungerGamesEffects> {

    @Override
    public Declaration declaration() {
        return HungerGamesCommands.RELOAD;
    }

    @Override
    public void run(final NordtalUser user, final Values values,
                    final HungerGamesEffects effects) {
        effects.async(() -> {
            final boolean sounds = effects.reloadSounds();
            final boolean messages = effects.reloadMessages();

            if (sounds && messages) {
                // No sound: an admin's confirmation of a command they just typed and are already
                // reading. /smp reload is silent for the same reason.
                user.reply("hg.admin.reloaded");
            } else {
                user.reply("hg.admin.reload-failed", Map.of(), Feedback.REFUSED);
            }
        });
    }
}
