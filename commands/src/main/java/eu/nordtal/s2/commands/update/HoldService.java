package eu.nordtal.s2.commands.update;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.message.context.ServiceContext;
import eu.nordtal.s2.common.update.RunRefused;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateKind;
import java.util.List;

/**
 * {@code /update down <service>} and {@code /update start [service]}: the two halves of one switch.
 *
 * {@code down} demands a service, since an unnamed scope would hold the whole network; {@code start} does not.
 */
public final class HoldService implements NordtalCommand<UpdateEffects> {

    private final Declaration declaration;

    public HoldService(final Declaration declaration) {
        this.declaration = declaration;
    }

    @Override
    public Declaration declaration() {
        return declaration;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final UpdateEffects effects) {
        final boolean down = UpdateCommands.DOWN.equals(declaration);
        final UpdateKind kind = down ? UpdateKind.DOWN : UpdateKind.START;
        final List<String> services =
                values.optionalString("service").map(List::of).orElseGet(List::of);

        effects.async(() -> {
            try {
                final long id = effects.submit(kind, user, services).id();
                effects.watch(id, user);
                if (down) {
                    user.reply(
                            MESSAGES.update()
                                    .down()
                                    .asked(
                                            new ServiceContext(services.isEmpty() ? "" : services.getFirst()),
                                            UpdateDirectory.UPDATE_COUNTDOWN.toSeconds()),
                            Feedback.SMALL_SUCCESS,
                            Tone.NEUTRAL);
                } else {
                    user.reply(MESSAGES.update().start().asked(), Feedback.SMALL_SUCCESS, Tone.NEUTRAL);
                }
            } catch (final RunRefused refused) {
                user.reply(Refusals.of(refused), Feedback.REFUSED, Tone.BAD);
            } catch (final RuntimeException failure) {
                user.reply(MESSAGES.update().writeFailed(), Feedback.REFUSED, Tone.BAD);
            }
        });
    }
}
