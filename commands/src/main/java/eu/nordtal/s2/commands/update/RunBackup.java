package eu.nordtal.s2.commands.update;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.update.RunRefused;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateKind;

/**
 * {@code /backup now} - count down, take the servers down, save the volumes, bring them back.
 *
 * A separate command and not {@code /update backup}: a different amount of damage asked for a
 * different reason, and the person who wants it is usually not updating anything - it is what
 * somebody runs before a change they are not sure about. Sharing the root would put it one
 * tab-completion away from {@code /update now}, which is the one command in this network whose
 * neighbours matter.
 *
 * It lives beside {@code /update} anyway: it writes the same row into the same table, is drawn by
 * the same {@link UpdateFollower}, and is carried out by the same container. Sharing
 * {@link UpdateEffects} means it is in the one list every process already registers
 * ({@code UpdateCommands.all()}), so a process cannot end up without it - which is the failure mode
 * {@code Target.LOCAL} carries: nothing travels, so nothing complains, so a process that forgot
 * simply has no {@code /backup} and no log line about it.
 */
public final class RunBackup implements NordtalCommand<UpdateEffects> {

    @Override
    public Declaration declaration() {
        return UpdateCommands.BACKUP;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final UpdateEffects effects) {
        effects.async(() -> {
            try {
                final long id = effects.submit(UpdateKind.BACKUP, user).id();
                effects.watch(id, user);
                // Accepted, not started: steward-worker can still refuse this run before any countdown.
                user.reply(
                        MESSAGES.backup().started(UpdateDirectory.UPDATE_COUNTDOWN.toSeconds()),
                        Feedback.SMALL_SUCCESS,
                        Tone.NEUTRAL);
            } catch (final RunRefused refused) {
                user.reply(Refusals.of(refused), Feedback.REFUSED, Tone.BAD);
            } catch (final RuntimeException failure) {
                user.reply(MESSAGES.update().writeFailed(), Feedback.REFUSED, Tone.BAD);
            }
        });
    }
}
