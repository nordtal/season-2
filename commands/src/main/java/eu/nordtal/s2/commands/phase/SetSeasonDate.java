package eu.nordtal.s2.commands.phase;

import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.phase.DateChange;
import eu.nordtal.s2.common.phase.SeasonDateRefused;
import eu.nordtal.s2.common.phase.SeasonDates;

import java.time.Instant;
import java.util.Optional;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

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
    public MessageRef what() {
        return launch ? MESSAGES.phase().date().what().launch() : MESSAGES.phase().date().what().smpStart();
    }

    /**
     * A date that is not one, before the confirmation rather than after it.
     *
     * <p>{@code /phase smp-start 2026-02-30} is a typo, and a typo that has to be typed twice before
     * being told it is a typo is the worst of both. {@code SeasonDates.parse} is the same check the
     * command would make anyway.</p>
     */
    @Override
    public java.util.Optional<MessageRef> problem(
            final Values values) {
        final String typed = values.string("when");
        if (SeasonDates.isClear(typed) || SeasonDates.parse(typed).isPresent()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(MESSAGES.phase().date().invalid(SeasonDates.PATTERN,
                SeasonDates.ZONE.getId(), SeasonDates.CLEAR));
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
                user.reply(
                        MESSAGES.phase().date().invalid(SeasonDates.PATTERN, SeasonDates.ZONE.getId(),
                                SeasonDates.CLEAR), Tone.BAD);
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
                user.reply(MESSAGES.phase().date().refused(refused.getMessage()),
                        Tone.BAD);
                return;
            } catch (final RuntimeException failure) {
                effects.warn("setting " + (launch ? "launch" : "smp-start"), failure);
                user.reply(MESSAGES.phase().date().failed(), Tone.BAD);
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
        final String what = user.phrase(what());

        if (change.current() == null) {
            user.reply(MESSAGES.phase().date().cleared(what), Tone.GOOD);
            if (!launch) {
                user.reply(MESSAGES.phase().date().kept(), Tone.MUTED);
            }
            return;
        }

        final String unset = user.phrase(MESSAGES.phase().date().unset());
        if (change.unchanged()) {
            user.reply(MESSAGES.phase().date().unchanged(what, SeasonDates.format(change.current(), unset)),
                    Tone.WARN);
        } else {
            user.reply(
                    MESSAGES.phase().date().set(what, SeasonDates.format(change.current(), unset),
                            SeasonDates.format(change.previous(), unset)), Tone.GOOD);
        }

        if (launch) {
            return;
        }
        if (!change.movedAccess()) {
            user.reply(MESSAGES.phase().date().noneMoved(), Tone.MUTED);
            return;
        }
        // Three keys and not four: one period belongs to one account, so "one period across several
        // accounts" cannot happen. Selecting here rather than writing "period(s)" is the rule
        // BundleContinuationTest enforces - a parenthetical plural is not a sentence in either
        // language, and in German it degenerates into "Zeitraum/Zeitraeume".
        final MessageRef moved = change.grants() == 1 ? MESSAGES.phase().date().movedSection().one()
                : change.accounts() == 1 ? MESSAGES.phase().date().movedSection().oneAccount(change.grants())
                : MESSAGES.phase().date().moved(change.grants(), change.accounts());
        // WARN, because this is the half of the command nobody asked for: moving smp-start moved
        // other people's paid access with it, and that is the sentence to notice.
        user.reply(moved, Tone.WARN);
    }
}
