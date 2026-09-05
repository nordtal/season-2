package eu.nordtal.s2.commands.access;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.phase.SeasonDates;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /access status <member>} - access, donor, language, every grant and every purchase.
 *
 * <h2>The long version of {@code /smp access}</h2>
 * {@code /smp access} is three lines an admin needs while standing next to somebody who cannot get
 * in. This is the whole file: it names the roles, the language, the history of grants and the
 * history of purchases, which is what somebody wants when the question is "why does this account
 * look like that" rather than "can they get in right now". Both exist on purpose; neither is the
 * other one truncated.
 *
 * <h2>Every line was hardcoded English until it moved here</h2>
 * Nine of them, built with a {@code StringBuilder}, in a bot whose entire message system exists so
 * that nothing is. That is not a matter of taste: the admins of this network are not all English
 * speakers, and docs/architecture.md calls a hardcoded string a bug rather than a shortcut.
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
                user.reply("access.failed", Map.of(), Feedback.REFUSED);
                return;
            }
            if (status.isEmpty()) {
                // The id is one Discord no longer has - somebody left, or the account was deleted.
                // Its own sentence, because the row is not wrong and the person is simply gone.
                user.reply("access.no-such-member", Map.of("discord", discordId),
                        Feedback.REFUSED);
                return;
            }

            final AccessEffects.Status account = status.get();
            user.reply("access.header", Map.of("player", account.name(), "discord", discordId));
            user.reply(account.accessUntil().isPresent() ? "access.until" : "access.none",
                    account.accessUntil()
                            .map(until -> Map.<String, Object>of("until", SeasonDates.format(until)))
                            .orElse(Map.of()));
            user.reply("access.donor",
                    Map.of("donor", user.phrase(account.donor() ? "access.yes" : "access.no")));
            user.reply("access.language", Map.of("language", account.locale().getLanguage()));
            user.reply("access.linked", Map.of("account",
                    account.minecraftAccount().map(UUID::toString)
                            .orElseGet(() -> user.phrase("access.none-linked"))));

            if (account.grants().isEmpty()) {
                user.reply("access.grants.none");
            } else {
                user.reply("access.grants.header");
                account.grants().forEach(grant -> user.reply(
                        grant.revoked() ? "access.grants.revoked" : "access.grants.line",
                        Map.of("from", SeasonDates.format(grant.validFrom()),
                                "until", SeasonDates.format(grant.validUntil()),
                                "source", grant.source())));
            }

            if (account.purchases().isEmpty()) {
                user.reply("access.purchases.none");
            } else {
                user.reply("access.purchases.header");
                account.purchases().forEach(purchase -> user.reply("access.purchases.line",
                        Map.of("reference", purchase.reference(),
                                "days", purchase.days(),
                                "amount", purchase.amount(),
                                "status", purchase.status())));
            }
        });
    }
}
