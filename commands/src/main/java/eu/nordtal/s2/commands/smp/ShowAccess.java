package eu.nordtal.s2.commands.smp;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.access.OpenPayment;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.message.context.DiscordMemberContext;
import eu.nordtal.s2.common.message.context.PlayerContext;
import eu.nordtal.s2.common.phase.SeasonDates;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /smp access <player>} - why can this person not get in?
 *
 * <h2>The third line is the one that pays for the command</h2>
 * "They have not paid" and "they are in the middle of paying" produce the same disconnect screen and
 * the same complaint, and only one of them means the admin should wait rather than act. It is
 * readable at all because the purchase flow's state is a row and not a cache - season 1 kept it in
 * memory, so a restart answered "setup expired" to everybody mid-purchase.
 *
 * <h2>Read-only, and answered to the asker alone</h2>
 * It carries somebody's Discord id and a payment reference. Those are things this network's admins
 * already see in the admin channel, and they are not things to print into a shared chat - so there
 * is no broadcast and no log line.
 *
 * <h2>A failure to read the payment does not discard the rest</h2>
 * The two reads are separate on purpose. The access line is the one an admin came for; losing it
 * because the second query failed would be the wrong trade.
 */
public final class ShowAccess implements NordtalCommand<SmpEffects> {

    @Override
    public Declaration declaration() {
        return SmpCommands.ACCESS;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final SmpEffects effects) {
        final UUID player = values.player("player");

        effects.async(() -> {
            final String name = ChangeAura.nameOr(effects, player);

            final Optional<SmpEffects.Access> access;
            try {
                access = effects.access(player);
            } catch (final RuntimeException failure) {
                effects.warn("/smp access could not read " + name, failure);
                user.reply(MESSAGES.smp().access().failed(), Feedback.REFUSED, Tone.BAD);
                return;
            }
            if (access.isEmpty() || access.get().discordId() == null) {
                // An unlinked account should not have got past the proxy at all, so this is worth
                // saying plainly rather than folding into "no access": it means something else is
                // already wrong.
                user.reply(MESSAGES.smp().access().unlinked(new PlayerContext(name)), Feedback.REFUSED, Tone.BAD);
                return;
            }

            final SmpEffects.Access state = access.get();
            user.reply(
                    MESSAGES.smp()
                            .access()
                            .linked(new PlayerContext(name), new DiscordMemberContext(state.discordId())),
                    Tone.NEUTRAL);

            if (state.accessActive() && state.validUntil() != null) {
                user.reply(MESSAGES.smp().access().active(SeasonDates.format(state.validUntil())), Tone.GOOD);
            } else if (state.validUntil() != null) {
                user.reply(MESSAGES.smp().access().expired(SeasonDates.format(state.validUntil())), Tone.WARN);
            } else {
                user.reply(MESSAGES.smp().access().never(), Tone.WARN);
            }

            final Optional<OpenPayment> pending;
            try {
                pending = effects.openPayment(state.discordId());
            } catch (final RuntimeException failure) {
                effects.warn("/smp access could not read the open payment for " + name, failure);
                user.reply(MESSAGES.smp().access().paymentUnknown(), Tone.BAD);
                return;
            }

            pending.ifPresentOrElse(
                    payment -> user.reply(
                            // A request with no bunq tab is somebody who picked a number of days and
                            // never got as far as a payment link, which is a different thing to
                            // chase.
                            payment.hasTab()
                                    ? MESSAGES.smp()
                                            .access()
                                            .payment(
                                                    payment.reference(),
                                                    payment.days(),
                                                    payment.amount(),
                                                    SeasonDates.format(payment.created()))
                                    : MESSAGES.smp()
                                            .access()
                                            .paymentUnstarted(
                                                    payment.reference(),
                                                    payment.days(),
                                                    SeasonDates.format(payment.created())),
                            Tone.MUTED),
                    () -> user.reply(MESSAGES.smp().access().noPayment(), Tone.MUTED));
        });
    }
}
