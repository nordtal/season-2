package eu.nordtal.s2.commands.access;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /access revoke <member>} - every running grant, at once.
 *
 * <p>Zero revoked is a legitimate answer and gets its own sentence: an admin who runs this on the
 * wrong person should be told nothing happened rather than reading "revoked 0 grants" and having
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
                user.reply("access.failed", Map.of(), Feedback.REFUSED, Tone.BAD);
                return;
            }
            user.reply(revoked == 0 ? "access.revoked.none"
                            : revoked == 1 ? "access.revoked.one" : "access.revoked",
                    Map.of("count", revoked),
                    revoked == 0 ? Feedback.REFUSED : Feedback.SMALL_SUCCESS,
                    // Nothing to revoke is WARN and not BAD: the command did what it was asked and
                    // found no grant, which is a fact about the account rather than a failure.
                    revoked == 0 ? Tone.WARN : Tone.GOOD);
        });
    }
}
