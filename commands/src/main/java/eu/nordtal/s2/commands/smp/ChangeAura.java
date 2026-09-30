package eu.nordtal.s2.commands.smp;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.messages.Tone;
import eu.nordtal.s2.messages.context.PlayerContext;
import eu.nordtal.s2.messages.feedback.Feedback;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /smp aura <player> <delta>}: a correction, with its reason recorded.
 *
 * Not confirmed, since the negative is an exact undo; an unlinked target is refused, as aura is per Discord id.
 */
public final class ChangeAura implements NordtalCommand<SmpEffects> {

    @Override
    public Declaration declaration() {
        return SmpCommands.AURA;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final SmpEffects effects) {
        final UUID player = values.player("player");
        final int delta = values.integer("delta");

        effects.async(() -> {
            final String name = nameOr(effects, player);
            final Optional<String> discordId;
            try {
                discordId = effects.discordIdOf(player);
            } catch (final RuntimeException failure) {
                effects.warn("/smp aura could not read the account link for " + name, failure);
                user.reply(MESSAGES.smp().admin().readFailed(), Feedback.REFUSED, Tone.BAD);
                return;
            }
            if (discordId.isEmpty()) {
                user.reply(MESSAGES.smp().admin().targetUnlinked(new PlayerContext(name)), Feedback.REFUSED, Tone.BAD);
                return;
            }

            try {
                effects.changeAura(player, discordId.get(), delta, user.name());
            } catch (final RuntimeException failure) {
                // Its own key, and not the read failure: changeAura books the aura row before it reads the new total.
                effects.warn("/smp aura " + name + " " + delta + " failed", failure);
                user.reply(
                        MESSAGES.smp().admin().auraUnknown(new PlayerContext(name), delta), Feedback.REFUSED, Tone.BAD);
                return;
            }
            user.reply(
                    MESSAGES.smp().admin().auraChanged(new PlayerContext(name), delta),
                    Feedback.SMALL_SUCCESS,
                    Tone.GOOD);
        });
    }

    /** Returns the player's name, or their UUID when the lookup itself fails, which must not end the task. */
    static String nameOr(final SmpEffects effects, final UUID player) {
        try {
            return effects.nameOf(player).orElse(player.toString());
        } catch (final RuntimeException failure) {
            effects.warn("could not read the name of " + player, failure);
            return player.toString();
        }
    }
}
