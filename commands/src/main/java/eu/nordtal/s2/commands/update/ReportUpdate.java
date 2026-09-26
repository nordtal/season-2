package eu.nordtal.s2.commands.update;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.update.RunRefused;
import eu.nordtal.s2.common.update.UpdateKind;

/** {@code /update} - ask what is newer than what the network is running. Changes nothing. */
public final class ReportUpdate implements NordtalCommand<UpdateEffects> {

    @Override
    public Declaration declaration() {
        return UpdateCommands.REPORT;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final UpdateEffects effects) {
        effects.async(() -> {
            try {
                final long id = effects.submit(UpdateKind.REPORT, user).id();
                effects.watch(id, user);
                // An acknowledgement and not the answer: surfaces read the row themselves.
                user.reply(MESSAGES.update().asked(), Feedback.SMALL_SUCCESS, Tone.GOOD);
            } catch (final RunRefused refused) {
                user.reply(Refusals.of(refused), Feedback.REFUSED, Tone.BAD);
            } catch (final RuntimeException failure) {
                user.reply(MESSAGES.update().writeFailed(), Feedback.REFUSED, Tone.BAD);
            }
        });
    }
}
