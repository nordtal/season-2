package eu.nordtal.s2.commands.phase;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.phase.SeasonDates;
import java.time.Instant;
import java.util.Optional;

/**
 * {@code /phase show}, also the bare {@code /phase} on the proxy; writes nothing.
 *
 * A process holding the phase says it before asking the database, so the answer survives a database that is down.
 */
public final class ShowPhase implements NordtalCommand<PhaseEffects> {

    @Override
    public Declaration declaration() {
        return PhaseCommands.SHOW;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final PhaseEffects effects) {
        final Optional<PhaseEffects.Observation> held = effects.observation();
        held.ifPresent(observation -> sayPhase(user, observation.phase(), observation.everRead()));

        effects.async(() -> {
            // Whether a phase line has gone out, which differs from whether this process held one.
            boolean saidThePhase = held.isPresent();
            final Instant launch;
            final Instant smpStart;
            try {
                if (held.isEmpty()) {
                    sayPhase(user, effects.phases().currentPhase(), true);
                    saidThePhase = true;
                }
                launch = effects.phases().launch().orElse(null);
                smpStart = effects.phases().smpStart().orElse(null);
            } catch (final RuntimeException failure) {
                effects.warn("reading the season dates for /phase show", failure);
                // Always an answer, worded by whether a phase line already went out.
                user.reply(
                        saidThePhase
                                ? MESSAGES.phase().read().failed()
                                : MESSAGES.phase().read().failedSection().only(),
                        Tone.BAD);
                return;
            }

            // "not set" is a state and is said in the asker's language, not in SeasonDates' English.
            final String unset = user.phrase(MESSAGES.phase().date().unset());
            user.reply(
                    MESSAGES.phase()
                            .dates(
                                    SeasonDates.format(launch, unset),
                                    SeasonDates.format(smpStart, unset),
                                    SeasonDates.ZONE.getId()),
                    Tone.MUTED);
        });
    }

    private static void sayPhase(final NordtalUser user, final SeasonPhase phase, final boolean everRead) {
        // The unread answer is WARN: it is the proxy's fallback, not an observation.
        user.reply(
                everRead
                        ? MESSAGES.phase().current(phase.name())
                        : MESSAGES.phase().currentSection().unread(phase.name()),
                everRead ? Tone.NEUTRAL : Tone.WARN);
    }
}
