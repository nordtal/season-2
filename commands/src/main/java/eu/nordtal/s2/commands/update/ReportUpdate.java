package eu.nordtal.s2.commands.update;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.update.UpdateKind;

import java.util.Map;

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
                // An acknowledgement and not the answer: resolving every source takes seconds and
                // the surfaces read the row themselves - Discord by editing its embed, the game by
                // printing the report when it lands. This command's job ends at "it has been asked
                // for".
                //
                // The id is deliberately not in the sentence. It was, and it is a database
                // primary key being read out to somebody who cannot do anything with it; the one
                // reader who can is looking at the updater's log, where it still is.
                user.reply("update.asked", Map.of(), Feedback.SMALL_SUCCESS);
            } catch (final RuntimeException failure) {
                user.reply("update.write-failed", Map.of(), Feedback.REFUSED);
            }
        });
    }
}
