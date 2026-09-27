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
 * {@code /phase launch <when>} and {@code /phase smp-start <when>}: the season's two dates.
 *
 * Moving smp-start shifts other people's unstarted grants, so the reply counts what it moved.
 */
public final class SetSeasonDate implements NordtalCommand<PhaseEffects> {

    private final boolean launch;

    private SetSeasonDate(final boolean launch) {
        this.launch = launch;
    }

    /** {@code /phase launch}: when the network opens. */
    public static SetSeasonDate launch() {
        return new SetSeasonDate(true);
    }

    /** {@code /phase smp-start}: when paid access starts running. */
    public static SetSeasonDate smpStart() {
        return new SetSeasonDate(false);
    }

    @Override
    public Declaration declaration() {
        return launch ? PhaseCommands.LAUNCH : PhaseCommands.SMP_START;
    }

    /** Returns the message key naming which date this is. */
    public MessageRef what() {
        return launch
                ? MESSAGES.phase().date().what().launch()
                : MESSAGES.phase().date().what().smpStart();
    }

    /** Refuses a date that is not one before the confirmation rather than after it. */
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
                // Not a failure: the model refused, in a sentence written for the person who typed it.
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
        // WARN: moving smp-start moved other people's paid periods, which nobody asked for.
        user.reply(moved, Tone.WARN);
    }
}
