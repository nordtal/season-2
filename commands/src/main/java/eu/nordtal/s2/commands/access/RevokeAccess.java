package eu.nordtal.s2.commands.access;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;

/** {@code /access revoke <member>}: every running grant at once, with its own sentence for none. */
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
                user.reply(MESSAGES.access().failed(), Feedback.REFUSED, Tone.BAD);
                return;
            }
            user.reply(
                    revoked == 0
                            ? MESSAGES.access().revokedSection().none()
                            : revoked == 1
                                    ? MESSAGES.access().revokedSection().one()
                                    : MESSAGES.access().revoked(revoked),
                    revoked == 0 ? Feedback.REFUSED : Feedback.SMALL_SUCCESS,
                    // Nothing to revoke is WARN, not BAD: the command did what it was asked.
                    revoked == 0 ? Tone.WARN : Tone.GOOD);
        });
    }
}
