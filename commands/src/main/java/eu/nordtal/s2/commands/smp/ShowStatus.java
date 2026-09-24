package eu.nordtal.s2.commands.smp;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;


import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

/** {@code /smp status}: three lines anybody may ask for. */
public final class ShowStatus implements NordtalCommand<SmpEffects> {

    @Override
    public Declaration declaration() {
        return SmpCommands.STATUS;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final SmpEffects effects) {
        effects.async(() -> {
            final SmpEffects.Status status;
            try {
                status = effects.status(user.locale());
            } catch (final RuntimeException failure) {
                effects.warn("/smp status failed", failure);
                user.reply(MESSAGES.smp().status().failed(), Feedback.REFUSED, Tone.BAD);
                return;
            }
            user.reply(MESSAGES.phase().current(status.phase()), Tone.NEUTRAL);
            if (status.milestone().isPresent()) {
                user.reply(MESSAGES.smp().status().milestone(status.milestone().get(), status.percent()),
                        Tone.NEUTRAL);
            } else {
                // Every milestone in the season is done. That is the one line here that is news.
                user.reply(MESSAGES.smp().status().finished(), Tone.GOOD);
            }
            // Three keys, chosen here rather than a "{online} player(s)" in one - the same rule
            // /phase's grant count already follows. A parenthetical plural is not a sentence in
            // either language, and in German it degenerates worse.
            user.reply(status.online() == 0 ? MESSAGES.smp().status().onlineSection().none() : status.online() == 1 ? MESSAGES.smp().status().onlineSection().one() : MESSAGES.smp().status().online(status.online()), Tone.MUTED);
        });
    }
}
