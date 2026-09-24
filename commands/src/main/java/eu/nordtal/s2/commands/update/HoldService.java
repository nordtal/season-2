package eu.nordtal.s2.commands.update;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.update.RunRefused;
import eu.nordtal.s2.common.update.UpdateDirectory;
import eu.nordtal.s2.common.update.UpdateKind;

import java.util.List;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

/**
 * {@code /update down <service>} and {@code /update start [service]} - the two halves of one switch
 * (season-2-ops/125).
 *
 * <h2>One class for both, for the reason {@link RunUpdate} gives</h2>
 * They differ by an enum value and by whether the argument is required. Everything else - the row,
 * the follower, the acknowledgement - is the same, and two classes would be two places for it to
 * drift.
 *
 * <h2>Why this exists at all when the interface has buttons</h2>
 * The buttons are the point of the ticket and this is not a second way of doing the same thing by
 * accident: it is the console, and the console is the one surface that does not depend on the thing
 * being stopped. A service put down from a laptop cannot be started again from a web interface that
 * is itself wedged, and "the interface is the only way back" is precisely the shape this network
 * avoids everywhere else ({@code Target.LOCAL}, and the paragraph above it in
 * {@link UpdateCommands}).
 *
 * <h2>The asymmetry in the argument is deliberate</h2>
 * {@code down} demands a service; {@code start} does not. An unnamed scope is the whole network
 * everywhere in this mechanism, and on the stopping side that reading is a network held down until
 * somebody presses a button - which nobody asks for by leaving a word off. On the starting side the
 * same reading is "start everything anybody is holding", which is the recovery an operator wants and
 * can do no harm that was not already asked for.
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
        final boolean down = declaration == UpdateCommands.DOWN;
        final UpdateKind kind = down ? UpdateKind.DOWN : UpdateKind.START;
        final List<String> services = values.optionalString("service")
                .map(List::of).orElseGet(List::of);

        effects.async(() -> {
            try {
                final long id = effects.submit(kind, user, services).id();
                effects.watch(id, user);
                if (down) {
                    user.reply(
                            MESSAGES.update().down().asked(services.isEmpty() ? "" : services.getFirst(),
                                    UpdateDirectory.UPDATE_COUNTDOWN.toSeconds()),
                            Feedback.SMALL_SUCCESS, Tone.NEUTRAL);
                } else {
                    user.reply(MESSAGES.update().start().asked(),
                            Feedback.SMALL_SUCCESS, Tone.NEUTRAL);
                }
            } catch (final RunRefused refused) {
                user.reply(Refusals.of(refused), Feedback.REFUSED, Tone.BAD);
            } catch (final RuntimeException failure) {
                user.reply(MESSAGES.update().writeFailed(), Feedback.REFUSED, Tone.BAD);
            }
        });
    }
}
