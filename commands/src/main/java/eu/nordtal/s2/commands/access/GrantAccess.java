package eu.nordtal.s2.commands.access;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.phase.SeasonDates;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /access grant <member> <days>} - days on top of whatever is already running.
 *
 * <h2>Appended, never replaced</h2>
 * The same rule a purchase follows: periods stack rather than being summed or reset, and a lapse
 * after the season opened starts today rather than back at the anchor.
 * {@code AccessDirectoryIntegrationTest} owns that arithmetic; this command only asks for it.
 *
 * <h2>Bounded, which the Discord command was not</h2>
 * It hand-checked "greater than zero" in its handler and had no upper bound at all, so a mistyped
 * {@code 3650} was a decade of free access and one keystroke away from {@code 365}. The bound is on
 * the declaration now, so Brigadier, Discord's own option validation and the request row all refuse
 * the same numbers.
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
                user.reply("access.failed", Map.of(), Feedback.REFUSED);
                return;
            }
            user.reply("access.granted",
                    Map.of("days", days, "until", SeasonDates.format(until)),
                    Feedback.BIG_SUCCESS);
        });
    }
}
