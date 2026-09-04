package eu.nordtal.s2.commands.phase;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.phase.DateChange;
import eu.nordtal.s2.common.phase.SeasonDateRefused;
import eu.nordtal.s2.common.phase.SeasonDates;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * {@code /phase launch &lt;when&gt;} and {@code /phase smp-start &lt;when&gt;} - the season's two
 * dates. One class, two instances, because the only thing that differs is which column is written
 * and one noun in the reply.
 *
 * <h2>The second one moves other people's money</h2>
 * {@code smp_start} is what a period bought weeks before the opening is anchored to, so moving it
 * shifts every grant that has not started yet - across accounts belonging to people who are not in
 * the room. The number of grants and accounts is reported for exactly that reason: it is the only
 * place an admin finds out that it happened. Clearing the date says so explicitly too, because
 * "nothing moved" and "there was nothing left to move it to" are different facts.
 *
 * <h2>Why the date is parsed before anything is deferred</h2>
 * Nothing has been read and nothing will be written, so a typo comes back immediately rather than
 * after a round trip - and on Discord it comes back without spending the interaction's three-second
 * acknowledgement window on a database that may be slow.
 */
public final class SetSeasonDate implements NordtalCommand<PhaseEffects> {

    private final boolean launch;

    private SetSeasonDate(final boolean launch) {
        this.launch = launch;
    }

    /** {@code /phase launch} - when the network opens. */
    public static SetSeasonDate launch() {
        return new SetSeasonDate(true);
    }

    /** {@code /phase smp-start} - when paid access starts running. */
    public static SetSeasonDate smpStart() {
        return new SetSeasonDate(false);
    }

    @Override
    public Declaration declaration() {
        return launch ? PhaseCommands.LAUNCH : PhaseCommands.SMP_START;
    }

    /** The message key naming which date this is, for the sentences that mention it. */
    public String whatKey() {
        return launch ? "phase.date.what.launch" : "phase.date.what.smp-start";
    }

    @Override
    public void run(final NordtalUser user, final Values values, final PhaseEffects effects) {
        final String typed = values.string("when");

        final Instant at;
        if (SeasonDates.isClear(typed)) {
            at = null;
        } else {
            final Optional<Instant> parsed = SeasonDates.parse(typed);
            if (parsed.isEmpty()) {
                user.reply("phase.date.invalid", Map.of(
                        "pattern", SeasonDates.PATTERN,
                        "zone", SeasonDates.ZONE.getId(),
                        "clear", SeasonDates.CLEAR));
                return;
            }
            at = parsed.get();
        }

        final String actor = user.discordId().orElse(null);

        effects.async(() -> {
            final DateChange change;
            try {
                change = launch
                        ? effects.phases().setLaunch(at, actor)
                        : effects.phases().setSmpStart(at, actor);
            } catch (final SeasonDateRefused refused) {
                // Not a failure: the model said no, in a sentence written for the person who typed
                // it. Nothing was written, so nothing is reported anywhere else.
                user.reply("phase.date.refused", Map.of("reason", refused.getMessage()));
                return;
            } catch (final RuntimeException failure) {
                effects.warn("setting " + whatKey(), failure);
                user.reply("phase.date.failed");
                return;
            }

            effects.recordDate(user, launch, change);
            effects.afterWrite();
            report(user, change);
        });
    }

    private void report(final NordtalUser user, final DateChange change) {
        // The noun is itself translated ("when the network opens" / "wann das Netzwerk öffnet"), so
        // it is rendered through the asker's own adapter and substituted, rather than written here
        // in one language.
        final String what = user.phrase(whatKey());

        if (change.current() == null) {
            user.reply("phase.date.cleared", Map.of("what", what));
            if (!launch) {
                user.reply("phase.date.kept");
            }
            return;
        }

        if (change.unchanged()) {
            user.reply("phase.date.unchanged", Map.of(
                    "what", what, "current", SeasonDates.format(change.current())));
        } else {
            user.reply("phase.date.set", Map.of(
                    "what", what,
                    "current", SeasonDates.format(change.current()),
                    "previous", SeasonDates.format(change.previous())));
        }

        if (launch) {
            return;
        }
        user.reply(change.movedAccess() ? "phase.date.moved" : "phase.date.none-moved",
                change.movedAccess()
                        ? Map.of("grants", String.valueOf(change.grants()),
                                "accounts", String.valueOf(change.accounts()))
                        : Map.of());
    }
}
