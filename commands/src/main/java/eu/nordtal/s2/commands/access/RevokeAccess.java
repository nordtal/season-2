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
 * {@code /access revoke <player>} - every running grant, at once.
 *
 * <p>Zero revoked is a legitimate answer and gets its own sentence: an admin who runs this on the
 * wrong person should be told nothing happened rather than reading "revoked 0 grant(s)" and having
 * to work out what that means.</p>
 */
public final class RevokeAccess implements NordtalCommand<AccessEffects> {

    @Override
    public Declaration declaration() {
        return AccessCommands.REVOKE;
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
            final int revoked;
            try {
                revoked = effects.revoke(discordId.get(), user);
            } catch (final RuntimeException failure) {
                effects.warn("/access revoke for " + discordId.get(), failure);
                user.reply("access.failed", Map.of(), Feedback.REFUSED);
                return;
            }
            user.reply(revoked == 0 ? "access.revoked.none" : "access.revoked",
                    Map.of("count", revoked),
                    revoked == 0 ? Feedback.REFUSED : Feedback.SMALL_SUCCESS);
        });
    }
}
