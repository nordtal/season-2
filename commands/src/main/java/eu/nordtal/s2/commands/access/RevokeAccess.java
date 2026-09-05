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
 * {@code /access revoke <member>} - every running grant, at once.
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
        final String discordId = values.account("member");

        effects.async(() -> {
            final int revoked;
            try {
                revoked = effects.revoke(discordId, user);
            } catch (final RuntimeException failure) {
                effects.warn("/access revoke for " + discordId, failure);
                user.reply("access.failed", Map.of(), Feedback.REFUSED);
                return;
            }
            user.reply(revoked == 0 ? "access.revoked.none" : "access.revoked",
                    Map.of("count", revoked),
                    revoked == 0 ? Feedback.REFUSED : Feedback.SMALL_SUCCESS);
        });
    }
}
