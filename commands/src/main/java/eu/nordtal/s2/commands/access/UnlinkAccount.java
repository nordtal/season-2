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
 * {@code /access unlink <member>} - break somebody else's account link.
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
        final String discordId = values.account("member");

        effects.async(() -> {
            final boolean unlinked;
            try {
                unlinked = effects.unlink(discordId, user);
            } catch (final RuntimeException failure) {
                effects.warn("/access unlink for " + discordId, failure);
                user.reply("access.failed", Map.of(), Feedback.REFUSED);
                return;
            }
            user.reply(unlinked ? "access.unlinked" : "access.not-linked",
                    Map.of("member", discordId),
                    unlinked ? Feedback.SMALL_SUCCESS : Feedback.REFUSED);
        });
    }
}
