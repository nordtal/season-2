package eu.nordtal.s2.commands.access;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.message.context.DiscordMemberContext;
import eu.nordtal.s2.common.message.context.PlayerContext;
import eu.nordtal.s2.common.phase.SeasonDates;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /access status <member>} - access, donor, language, every grant and every purchase.
 *
 * The long form of {@code /smp access}, which answers only "can they get in right now". Both
 * exist on purpose; neither is the other truncated.
 */
public final class ShowStatus implements NordtalCommand<AccessEffects> {

    @Override
    public Declaration declaration() {
        return AccessCommands.STATUS;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final AccessEffects effects) {
        final String discordId = values.account("member");

        effects.async(() -> {
            final Optional<AccessEffects.Status> status = readStatus(user, effects, discordId);
            if (status.isEmpty()) {
                return;
            }

            final AccessEffects.Status account = status.get();
            replySummary(user, discordId, account);
            replyGrants(user, account);
            replyPurchases(user, account);
        });
    }

    private static Optional<AccessEffects.Status> readStatus(
            final NordtalUser user, final AccessEffects effects, final String discordId) {
        final Optional<AccessEffects.Status> status;
        try {
            status = effects.status(discordId);
        } catch (final RuntimeException failure) {
            effects.warn("/access status could not read " + discordId, failure);
            user.reply(MESSAGES.access().failed(), Feedback.REFUSED, Tone.BAD);
            return Optional.empty();
        }
        if (status.isEmpty()) {
            // A member id Discord no longer resolves: the row is not wrong, the person is gone.
            user.reply(MESSAGES.access().noSuchMember(new DiscordMemberContext(discordId)), Feedback.REFUSED, Tone.BAD);
        }
        return status;
    }

    private static void replySummary(
            final NordtalUser user, final String discordId, final AccessEffects.Status account) {
        // The tones shape the readout: the header says who, one line carries the news, the rest is detail.
        user.reply(
                MESSAGES.access().header(new PlayerContext(account.name()), new DiscordMemberContext(discordId)),
                Tone.NEUTRAL);
        user.reply(
                account.accessUntil()
                        .map(until -> MESSAGES.access().until(SeasonDates.format(until)))
                        .orElseGet(MESSAGES.access()::none),
                account.accessUntil().isPresent() ? Tone.GOOD : Tone.WARN);
        user.reply(
                MESSAGES.access()
                        .donor(user.phrase(
                                account.donor()
                                        ? MESSAGES.access().yes()
                                        : MESSAGES.access().no())),
                Tone.MUTED);
        user.reply(MESSAGES.access().language(account.locale().getLanguage()), Tone.MUTED);
        user.reply(
                MESSAGES.access()
                        .linked(account.minecraftAccount()
                                .map(UUID::toString)
                                .orElseGet(() -> user.phrase(MESSAGES.access().noneLinked()))),
                Tone.MUTED);
    }

    private static void replyGrants(final NordtalUser user, final AccessEffects.Status account) {
        if (account.grants().isEmpty()) {
            user.reply(MESSAGES.access().grants().none(), Tone.MUTED);
            return;
        }
        user.reply(MESSAGES.access().grants().header(), Tone.NEUTRAL);
        account.grants()
                .forEach(grant -> user.reply(
                        grant.revoked()
                                ? MESSAGES.access()
                                        .grants()
                                        .revoked(
                                                SeasonDates.format(grant.validFrom()),
                                                SeasonDates.format(grant.validUntil()),
                                                grant.source())
                                : MESSAGES.access()
                                        .grants()
                                        .line(
                                                SeasonDates.format(grant.validFrom()),
                                                SeasonDates.format(grant.validUntil()),
                                                grant.source()),
                        Tone.MUTED));
    }

    private static void replyPurchases(final NordtalUser user, final AccessEffects.Status account) {
        if (account.purchases().isEmpty()) {
            user.reply(MESSAGES.access().purchases().none(), Tone.MUTED);
            return;
        }
        user.reply(MESSAGES.access().purchases().header(), Tone.NEUTRAL);
        account.purchases()
                .forEach(purchase -> user.reply(
                        MESSAGES.access()
                                .purchases()
                                .line(purchase.reference(), purchase.days(), purchase.amount(), purchase.status()),
                        Tone.MUTED));
    }
}
