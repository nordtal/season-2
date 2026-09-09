package eu.nordtal.s2.commands.smp;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;

import java.util.Map;

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
                user.reply("smp.status.failed", Map.of(), Feedback.REFUSED, Tone.BAD);
                return;
            }
            user.reply("phase.current", Map.of("phase", status.phase()), Tone.NEUTRAL);
            if (status.milestone().isPresent()) {
                user.reply("smp.status.milestone", Map.of(
                        "milestone", status.milestone().get(), "percent", status.percent()),
                        Tone.NEUTRAL);
            } else {
                // Every milestone in the season is done. That is the one line here that is news.
                user.reply("smp.status.finished", Map.of(), Tone.GOOD);
            }
            user.reply("smp.status.online", Map.of("online", status.online()), Tone.MUTED);
        });
    }
}
