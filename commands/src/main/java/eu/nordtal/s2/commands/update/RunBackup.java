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
 * {@code /backup now}: counts down, takes the servers down, saves the volumes and brings them back.
 *
 * Its own root, away from {@code /update now}, yet registered through {@code UpdateCommands.all()} like it.
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
