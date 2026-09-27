package eu.nordtal.s2.commands.access;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.message.context.DiscordMemberContext;

/** {@code /access unlink <member>}: breaks somebody else's link, confirmed since only the player can relink. */
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
                user.reply(MESSAGES.access().failed(), Feedback.REFUSED, Tone.BAD);
                return;
            }
            user.reply(
                    unlinked
                            ? MESSAGES.access().unlinked(new DiscordMemberContext(discordId))
                            : MESSAGES.access().notLinked(),
                    unlinked ? Feedback.SMALL_SUCCESS : Feedback.REFUSED,
                    unlinked ? Tone.GOOD : Tone.WARN);
        });
    }
}
