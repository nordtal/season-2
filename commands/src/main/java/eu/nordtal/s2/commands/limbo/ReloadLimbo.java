package eu.nordtal.s2.commands.limbo;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;

import java.util.Map;

/** {@code /limbo reload} - re-read the wording without taking the waiting room down. */
public final class ReloadLimbo implements NordtalCommand<LimboEffects> {

    @Override
    public Declaration declaration() {
        return LimboCommands.RELOAD;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final LimboEffects effects) {
        effects.async(() -> user.reply(
                effects.reloadMessages() ? "limbo.admin.reloaded" : "limbo.admin.reload-failed",
                Map.of(), Feedback.SMALL_SUCCESS));
    }
}
