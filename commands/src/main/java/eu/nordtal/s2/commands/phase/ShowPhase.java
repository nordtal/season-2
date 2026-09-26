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
 * {@code /phase show} - and the bare {@code /phase} on the proxy. Reads, writes nothing.
 *
 * The phase can come out before the database is asked because this is the command somebody runs
 * while the network is misbehaving, so a process holding the
 * phase in memory says it <em>first</em> and asks the database afterwards. The proxy does hold it
 * ({@code PhaseWatch}); the bot does not, and reads. Both paths end with the same two lines in the
 * same order, which is exactly what {@link PhaseEffects#observation()} exists to make possible
 * without either process knowing about the other.
 *
 * Whether the first line is an observation or the never-read fallback is stated rather than
 * hidden: "the network is in MAINTENANCE" and "the network has not been readable, so it is being
 * treated as MAINTENANCE" are different facts, and only one of them is a reason to panic.
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
            // Whether a phase line has actually gone out, which is NOT the same question as whether this process had.
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
                // Always an answer, and which one depends on whether a phase line went out: phase.read.failed says.
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
        // The unread answer is WARN and not NEUTRAL: it is the proxy's own cache answering because nothing has ever.
        user.reply(
                everRead
                        ? MESSAGES.phase().current(phase.name())
                        : MESSAGES.phase().currentSection().unread(phase.name()),
                everRead ? Tone.NEUTRAL : Tone.WARN);
    }
}
