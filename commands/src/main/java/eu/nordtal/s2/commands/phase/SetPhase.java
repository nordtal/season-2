package eu.nordtal.s2.commands.phase;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Phase;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.message.MessageRef;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.phase.PhaseChange;
import java.util.Optional;

/**
 * {@code /phase set <phase>}: the one command that can disconnect everybody.
 *
 * Parses the name itself: {@code SeasonPhase.fromDatabase} maps anything unknown to {@code MAINTENANCE}.
 */
public final class SetPhase implements NordtalCommand<PhaseEffects> {

    @Override
    public Declaration declaration() {
        return PhaseCommands.SET;
    }

    /** Refuses an unknown phase name before the confirmation rather than after the retype. */
    @Override
    public java.util.Optional<MessageRef> problem(final Values values) {
        final String requested = values.string("phase");
        if (parse(requested).isPresent()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(MESSAGES.phase().unknown(requested, PhaseCommands.names()));
    }

    @Override
    public void run(final NordtalUser user, final Values values, final PhaseEffects effects) {
        final String requested = values.string("phase");
        final Optional<SeasonPhase> target = parse(requested);
        if (target.isEmpty()) {
            user.reply(MESSAGES.phase().unknown(requested, PhaseCommands.names()), Tone.BAD);
            return;
        }

        final String actor = user.discordId().orElse(null);
        final String reason = "/phase set from " + user.origin() + " by " + user.name();

        effects.async(() -> {
            final PhaseChange change;
            try {
                change = effects.phases().switchPhase(target.get(), actor, reason);
            } catch (final RuntimeException failure) {
                effects.warn("switching the season phase to " + target.get(), failure);
                user.reply(MESSAGES.phase().failed(), Tone.BAD);
                return;
            }

            effects.recordSwitch(user, change);
            // Do not wait for the poll or the notification to come back around: this process already knows.
            effects.afterWrite();

            user.reply(
                    change.unchanged()
                            ? MESSAGES.phase().unchanged(change.current().name())
                            : MESSAGES.phase()
                                    .changed(
                                            String.valueOf(change.previous()),
                                            change.current().name()),
                    // "already in that phase" is WARN: nothing was written.
                    change.unchanged() ? Tone.WARN : Tone.GOOD);
        });
    }

    /**
     * Resolves what somebody typed or picked, case-insensitively.
     *
     * @param value the raw argument, may be {@code null}
     * @return the phase, or empty when it is not one
     */
    public static Optional<SeasonPhase> parse(final String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (final SeasonPhase phase : SeasonPhase.values()) {
            if (phase.name().equalsIgnoreCase(value)) {
                return Optional.of(phase);
            }
        }
        return Optional.empty();
    }

    /** Returns what a switch to {@code phase} does to everybody online. */
    public static MessageRef consequence(final SeasonPhase phase) {
        final Phase.Consequence consequence = MESSAGES.phase().consequence();
        return switch (phase) {
            case PRE_LAUNCH -> consequence.preLaunch();
            case PRE_EVENT -> consequence.preEvent();
            case START_EVENT -> consequence.startEvent();
            case SMP -> consequence.smp();
            case MAINTENANCE -> consequence.maintenance();
        };
    }
}
