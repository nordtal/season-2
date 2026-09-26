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
 * {@code /update now} and {@code /update restart} - the two that take servers away.
 *
 * One class for both, because they differ by one enum value:
 * both stop the services, both count down for {@link UpdateDirectory#UPDATE_COUNTDOWN}, both are
 * confirmed before the countdown even starts, and both are cancelled by the same
 * {@code /update cancel}. The only difference is whether jars move in the gap - which is the
 * steward-worker's business and not this command's. Two classes would be two places for the countdown
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
        final UpdateKind kind = UpdateCommands.RESTART.equals(declaration) ? UpdateKind.RESTART : UpdateKind.UPDATE;

        effects.async(() -> {
            try {
                final long id = effects.submit(kind, user).id();
                effects.watch(id, user);
                // Accepted, not started: submit() writes a row.
                user.reply(
                        MESSAGES.update().started(UpdateDirectory.UPDATE_COUNTDOWN.toSeconds()),
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
