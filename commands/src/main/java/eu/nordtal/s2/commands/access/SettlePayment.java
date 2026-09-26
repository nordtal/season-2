package eu.nordtal.s2.commands.access;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.phase.SeasonDates;
import java.util.Objects;

/**
 * {@code /access settle <reference>} - book a payment by hand.
 *
 * The one manual path out of the automatic one: a payment on a reference that is not
 * {@code OPEN} is never booked automatically; it goes to the
 * admin channel, and this is what an admin runs afterwards. So the two refusals below are the whole
 * point of the command: an unknown reference is a typo, and a reference that is not open is the
 * automatic path having already dealt with it - which are opposite problems and must not share a
 * sentence.
 */
public final class SettlePayment implements NordtalCommand<AccessEffects> {

    @Override
    public Declaration declaration() {
        return AccessCommands.SETTLE;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final AccessEffects effects) {
        final String reference = values.string("reference");

        effects.async(() -> {
            final AccessEffects.Settled settled;
            try {
                settled = effects.settle(reference, user);
            } catch (final RuntimeException failure) {
                effects.warn("/access settle " + reference, failure);
                user.reply(MESSAGES.access().failed(), Feedback.REFUSED, Tone.BAD);
                return;
            }

            switch (settled.outcome()) {
                case UNKNOWN -> user.reply(MESSAGES.access().settle().unknown(reference), Feedback.REFUSED, Tone.BAD);
                // WARN and not BAD: the reference exists and nothing went wrong.
                case NOT_OPEN ->
                    user.reply(
                            MESSAGES.access()
                                    .settle()
                                    .notOpen(reference, Objects.requireNonNull(settled.status(), "status")),
                            Feedback.REFUSED,
                            Tone.WARN);
                case BOOKED ->
                    user.reply(
                            MESSAGES.access()
                                    .settle()
                                    .booked(
                                            reference,
                                            settled.days(),
                                            SeasonDates.format(Objects.requireNonNull(settled.until(), "until"))),
                            Feedback.BIG_SUCCESS,
                            Tone.GOOD);
            }
        });
    }
}
