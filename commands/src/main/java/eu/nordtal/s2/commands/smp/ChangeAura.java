package eu.nordtal.s2.commands.smp;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /smp aura <player> <delta>} - a correction, with its reason recorded.
 *
 * <h2>Not confirmed, deliberately</h2>
 * Applying the negative is an exact undo, which is what a confirmation would otherwise be protecting
 * against. Guarding it as well would train an admin to type every {@code /smp} command twice, and
 * that is how the guard on the one that deletes a world stops being read.
 *
 * <h2>Aura is credited to a Discord account, not to a Minecraft one</h2>
 * Which is why an unlinked target is refused rather than half-applied: {@code smp_aura_event} is
 * keyed on the Discord id, so there is genuinely nothing to write. The sentence that comes back is
 * addressed to the <b>admin</b> about somebody else - before 2026-09-05 this answered with the
 * message written for a player about their own account, which told an admin the wrong thing about
 * the person standing in front of them.
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
                user.reply("smp.admin.read-failed", Map.of(), Feedback.REFUSED);
                return;
            }
            if (discordId.isEmpty()) {
                user.reply("smp.admin.target-unlinked", Map.of("player", name), Feedback.REFUSED);
                return;
            }

            try {
                effects.changeAura(player, discordId.get(), delta, user.name());
            } catch (final RuntimeException failure) {
                // Its own key, and not the read failure: changeAura books the aura row before it
                // reads the new total back, so a throw here can be either half. "Nothing changed"
                // would be a claim this branch cannot make.
                effects.warn("/smp aura " + name + " " + delta + " failed", failure);
                user.reply("smp.admin.aura-unknown", Map.of("player", name, "delta", delta),
                        Feedback.REFUSED);
                return;
            }
            user.reply("smp.admin.aura-changed", Map.of("player", name, "delta", delta),
                    Feedback.SMALL_SUCCESS);
        });
    }

    /**
     * The player's name, or their UUID when the lookup itself fails.
     *
     * <p>The name is decoration - what the admin came for is the aura change - so a failure here
     * must not end the task before anything has been said.</p>
     */
    static String nameOr(final SmpEffects effects, final UUID player) {
        try {
            return effects.nameOf(player).orElse(player.toString());
        } catch (final RuntimeException failure) {
            effects.warn("could not read the name of " + player, failure);
            return player.toString();
        }
    }
}
