package eu.nordtal.s2.commands.access;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.phase.SeasonDates;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /access status <member>} - access, donor, language, every grant and every purchase.
 *
 * <p>The long form of {@code /smp access}, which answers only "can they get in right now". Both
 * exist on purpose; neither is the other truncated.</p>
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
            final Optional<AccessEffects.Status> status;
            try {
                status = effects.status(discordId);
            } catch (final RuntimeException failure) {
                effects.warn("/access status could not read " + discordId, failure);
                user.reply("access.failed", Map.of(), Feedback.REFUSED, Tone.BAD);
                return;
            }
            if (status.isEmpty()) {
                // The id is one Discord no longer has: the row is not wrong, the person is gone.
                user.reply("access.no-such-member", Map.of("discord", discordId),
                        Feedback.REFUSED, Tone.BAD);
                return;
            }

            final AccessEffects.Status account = status.get();
            // The tones shape the readout: the header says who, one line carries the news, the
            // rest is detail.
            user.reply("access.header", Map.of("player", account.name(), "discord", discordId),
                    Tone.NEUTRAL);
            user.reply(account.accessUntil().isPresent() ? "access.until" : "access.none",
                    account.accessUntil()
                            .map(until -> Map.<String, Object>of("until", SeasonDates.format(until)))
                            .orElse(Map.of()),
                    account.accessUntil().isPresent() ? Tone.GOOD : Tone.WARN);
            user.reply("access.donor",
                    Map.of("donor", user.phrase(account.donor() ? "access.yes" : "access.no")),
                    Tone.MUTED);
            user.reply("access.language", Map.of("language", account.locale().getLanguage()),
                    Tone.MUTED);
            user.reply("access.linked", Map.of("account",
                    account.minecraftAccount().map(UUID::toString)
                            .orElseGet(() -> user.phrase("access.none-linked"))), Tone.MUTED);

            if (account.grants().isEmpty()) {
                user.reply("access.grants.none", Map.of(), Tone.MUTED);
            } else {
                user.reply("access.grants.header", Map.of(), Tone.NEUTRAL);
                account.grants().forEach(grant -> user.reply(
                        grant.revoked() ? "access.grants.revoked" : "access.grants.line",
                        Map.of("from", SeasonDates.format(grant.validFrom()),
                                "until", SeasonDates.format(grant.validUntil()),
                                "source", grant.source()),
                        Tone.MUTED));
            }

            if (account.purchases().isEmpty()) {
                user.reply("access.purchases.none", Map.of(), Tone.MUTED);
            } else {
                user.reply("access.purchases.header", Map.of(), Tone.NEUTRAL);
                account.purchases().forEach(purchase -> user.reply("access.purchases.line",
                        Map.of("reference", purchase.reference(),
                                "days", purchase.days(),
                                "amount", purchase.amount(),
                                "status", purchase.status()),
                        Tone.MUTED));
            }
        });
    }
}
