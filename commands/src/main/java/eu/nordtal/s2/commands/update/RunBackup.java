package eu.nordtal.s2.commands.update;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateKind;

import java.util.Map;

/**
 * {@code /backup now} - count down, take the servers down, save the volumes, bring them back.
 *
 * <h2>Why it is a separate command and not {@code /update backup}</h2>
 * Because it is a different amount of damage asked for a different reason, and because the person
 * who wants it is usually not updating anything: it is what somebody runs before a change they are
 * not sure about. Sharing the root would put it one tab-completion away from {@code /update now},
 * which is the one command in this network whose neighbours matter.
 *
 * <h2>Why the class lives beside {@code /update} anyway</h2>
 * It writes the same row into the same table, is drawn by the same {@link UpdateFollower}, and is
 * carried out by the same container. Sharing {@link UpdateEffects} means it is in the one list
 * every process already registers ({@code UpdateCommands.all()}), so a process cannot end up
 * without it - which is the failure mode {@code Target.LOCAL} carries: nothing travels, so nothing
 * complains, so a process that forgot simply has no {@code /backup} and no log line about it.
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
                // Accepted, not started. submit() writes a row and nothing more: the updater can
                // still refuse this run before any countdown - Arcane unreachable, an empty
                // backup.volumes, another run holding the lock - and a line that says "backing up"
                // would then be the last thing anybody was told. The watcher reports the countdown
                // once the updater has actually recorded it, which is where that news belongs.
                // SMALL_SUCCESS rather than BIG_SUCCESS for the same reason: the sound says the
                // command was taken, not that the network is about to go down.
                user.reply("backup.started", Map.of(
                        "seconds", UpdateDirectory.UPDATE_COUNTDOWN.toSeconds()),
                        Feedback.SMALL_SUCCESS, Tone.NEUTRAL);
            } catch (final RuntimeException failure) {
                user.reply("update.write-failed", Map.of(), Feedback.REFUSED, Tone.BAD);
            }
        });
    }
}
