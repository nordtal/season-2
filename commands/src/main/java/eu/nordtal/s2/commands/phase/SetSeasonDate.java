package eu.nordtal.s2.commands.phase;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.phase.DateChange;
import eu.nordtal.s2.common.phase.SeasonDateRefused;
import eu.nordtal.s2.common.phase.SeasonDates;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code /phase launch <when>} and {@code /phase smp-start <when>} - the season's two dates.
 *
 * One class, two instances, because the only thing that differs is which column is written and
 * one noun in the reply.
 *
 * The second one moves other people's money. {@code smp_start} is what a period bought weeks
 * before the opening is anchored to, so moving it shifts every grant that has not started yet -
 * across accounts belonging to people who are not in the room. The number of grants and accounts
 * is reported for exactly that reason: it is the only place an admin finds out that it happened.
 * Clearing the date says so explicitly too, because "nothing moved" and "there was nothing left
 * to move it to" are different facts.
 *
 * The date is parsed before anything is deferred: nothing has been read and nothing will be
 * written, so a typo comes back immediately rather than after a round trip - and on Discord it
 * comes back without spending the interaction's three-second acknowledgement window on a database
 * that may be slow.
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
        return launch
                ? MESSAGES.phase().date().what().launch()
                : MESSAGES.phase().date().what().smpStart();
    }

    /**
     * A date that is not one, before the confirmation rather than after it.
     *
     * A date typo (a February 30th, say) is the worst possible thing to be told about only after
     * confirming twice. {@code SeasonDates.parse} is the same check the command would make anyway.
     */
    @Override
    public java.util.Optional<MessageRef> problem(final Values values) {
        final String typed = values.string("when");
        if (SeasonDates.isClear(typed) || SeasonDates.parse(typed).isPresent()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(
                MESSAGES.phase().date().invalid(SeasonDates.PATTERN, SeasonDates.ZONE.getId(), SeasonDates.CLEAR));
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
                        MESSAGES.phase()
                                .date()
                                .invalid(SeasonDates.PATTERN, SeasonDates.ZONE.getId(), SeasonDates.CLEAR),
                        Tone.BAD);
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
                // Not a failure: the model said no, in a sentence written for the person who typed it. Nothing was.
                user.reply(
                        MESSAGES.phase().date().refused(Objects.requireNonNull(refused.getMessage(), "message")),
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
        // The noun is itself translated ("when the network opens" / "wann das Netzwerk öffnet").
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
            user.reply(MESSAGES.phase().date().unchanged(what, SeasonDates.format(change.current(), unset)), Tone.WARN);
        } else {
            user.reply(
                    MESSAGES.phase()
                            .date()
                            .set(
                                    what,
                                    SeasonDates.format(change.current(), unset),
                                    SeasonDates.format(change.previous(), unset)),
                    Tone.GOOD);
        }

        if (launch) {
            return;
        }
        if (!change.movedAccess()) {
            user.reply(MESSAGES.phase().date().noneMoved(), Tone.MUTED);
            return;
        }
        // Three keys and not four: one period belongs to one account.
        final MessageRef moved = change.grants() == 1
                ? MESSAGES.phase().date().movedSection().one()
                : change.accounts() == 1
                        ? MESSAGES.phase().date().movedSection().oneAccount(change.grants())
                        : MESSAGES.phase().date().moved(change.grants(), change.accounts());
        // WARN, because this is the half of the command nobody asked for: moving smp-start moved other people's paid.
        user.reply(moved, Tone.WARN);
    }
}
