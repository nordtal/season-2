package eu.nordtal.s2.commands.access;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.phase.SeasonDates;
import java.time.Instant;

/**
 * {@code /access grant <member> <days>} - days on top of whatever is already running.
 *
 * Appended, never replaced: the same rule a purchase follows, periods stack rather than being
 * summed or reset, and a lapse after the season opened starts today rather than back at the
 * anchor. {@code AccessDirectoryIntegrationTest} owns that arithmetic; this command only asks for
 * it.
 *
 * Bounded on the declaration, so Brigadier, Discord's own option validation and the request row
 * all refuse the same numbers.
 */
public final class GrantAccess implements NordtalCommand<AccessEffects> {

    @Override
    public Declaration declaration() {
        return AccessCommands.GRANT;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final AccessEffects effects) {
        final String discordId = values.account("member");
        final int days = values.integer("days");

        effects.async(() -> {
            final Instant until;
            try {
                until = effects.grant(discordId, days, user);
            } catch (final RuntimeException failure) {
                effects.warn("/access grant " + days + " days to " + discordId, failure);
                user.reply(MESSAGES.access().failed(), Feedback.REFUSED, Tone.BAD);
                return;
            }
            user.reply(MESSAGES.access().granted(days, SeasonDates.format(until)), Feedback.BIG_SUCCESS, Tone.GOOD);
        });
    }
}
