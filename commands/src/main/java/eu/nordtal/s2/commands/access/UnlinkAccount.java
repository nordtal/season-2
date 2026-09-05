package eu.nordtal.s2.commands.access;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /access unlink <player>} - break somebody else's account link.
 *
 * <p>For a player who has lost the Discord or Minecraft account they linked. Confirmed because the
 * admin who does it cannot undo it: re-linking needs a code the <em>player</em> generates in game,
 * so an accidental unlink leaves somebody outside the login gate until they can get to a client.</p>
 */
public final class UnlinkAccount implements NordtalCommand<AccessEffects> {

    @Override
    public Declaration declaration() {
        return AccessCommands.UNLINK;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final AccessEffects effects) {
        final UUID player = values.player("player");

        effects.async(() -> {
            final Optional<String> discordId = effects.discordIdOf(player);
            if (discordId.isEmpty()) {
                user.reply("access.not-linked", Map.of("player", player.toString()),
                        Feedback.REFUSED);
                return;
            }
            final boolean unlinked;
            try {
                unlinked = effects.unlink(discordId.get(), user);
            } catch (final RuntimeException failure) {
                effects.warn("/access unlink for " + discordId.get(), failure);
                user.reply("access.failed", Map.of(), Feedback.REFUSED);
                return;
            }
            user.reply(unlinked ? "access.unlinked" : "access.not-linked",
                    Map.of("player", player.toString()),
                    unlinked ? Feedback.SMALL_SUCCESS : Feedback.REFUSED);
        });
    }
}
