package eu.nordtal.s2.commands.smp;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.access.OpenPayment;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.phase.SeasonDates;

import java.util.Map;
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
            final String name = effects.nameOf(player).orElse(player.toString());

            final Optional<SmpEffects.Access> access;
            try {
                access = effects.access(player);
            } catch (final RuntimeException failure) {
                effects.warn("/smp access could not read " + name, failure);
                user.reply("smp.access.failed", Map.of(), Feedback.REFUSED);
                return;
            }
            if (access.isEmpty() || access.get().discordId() == null) {
                // An unlinked account should not have got past the proxy at all, so this is worth
                // saying plainly rather than folding into "no access": it means something else is
                // already wrong.
                user.reply("smp.access.unlinked", Map.of("player", name), Feedback.REFUSED);
                return;
            }

            final SmpEffects.Access state = access.get();
            user.reply("smp.access.linked", Map.of("player", name, "discord", state.discordId()));

            if (state.accessActive() && state.validUntil() != null) {
                user.reply("smp.access.active",
                        Map.of("until", SeasonDates.format(state.validUntil())));
            } else if (state.validUntil() != null) {
                user.reply("smp.access.expired",
                        Map.of("since", SeasonDates.format(state.validUntil())));
            } else {
                user.reply("smp.access.never");
            }

            final Optional<OpenPayment> pending;
            try {
                pending = effects.openPayment(state.discordId());
            } catch (final RuntimeException failure) {
                effects.warn("/smp access could not read the open payment for " + name, failure);
                user.reply("smp.access.payment-unknown");
                return;
            }

            pending.ifPresentOrElse(
                    payment -> user.reply(
                            // A request with no bunq tab is somebody who picked a number of days and
                            // never got as far as a payment link, which is a different thing to
                            // chase.
                            payment.hasTab() ? "smp.access.payment" : "smp.access.payment-unstarted",
                            Map.of("reference", payment.reference(),
                                    "days", payment.days(),
                                    "amount", payment.amount(),
                                    "since", SeasonDates.format(payment.created()))),
                    () -> user.reply("smp.access.no-payment"));
        });
    }
}
