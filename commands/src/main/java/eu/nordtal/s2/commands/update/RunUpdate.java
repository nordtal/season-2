package eu.nordtal.s2.commands.update;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateKind;

import java.util.Map;

/**
 * {@code /update now} and {@code /update restart} - the two that take servers away.
 *
 * <h2>One class for both, because they differ by one enum value</h2>
 * Both stop the services, both count down for {@link UpdateDirectory#UPDATE_COUNTDOWN}, both are
 * confirmed before the countdown even starts, and both are cancelled by the same
 * {@code /update cancel}. The only difference is whether jars move in the gap - which is the
 * updater's business and not this command's. Two classes would be two places for the countdown
 * length and the confirmation to drift apart.
 */
public final class RunUpdate implements NordtalCommand<UpdateEffects> {

    private final Declaration declaration;

    public RunUpdate(final Declaration declaration) {
        this.declaration = declaration;
    }

    @Override
    public Declaration declaration() {
        return declaration;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final UpdateEffects effects) {
        final UpdateKind kind = declaration == UpdateCommands.RESTART
                ? UpdateKind.RESTART : UpdateKind.UPDATE;

        effects.async(() -> {
            try {
                final long id = effects.submit(kind, user.name()).id();
                effects.watch(id, user);
                // Everybody else is told by the proxy, which is the only process that sees every
                // player. This line is for the person who typed it, and its job is to name the way
                // back out while there still is one.
                user.reply("update.started", Map.of(
                        "id", id,
                        "seconds", UpdateDirectory.UPDATE_COUNTDOWN.toSeconds()), Feedback.BIG_SUCCESS);
            } catch (final RuntimeException failure) {
                user.reply("update.write-failed", Map.of(), Feedback.REFUSED);
            }
        });
    }
}
