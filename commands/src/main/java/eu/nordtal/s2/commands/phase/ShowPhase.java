package eu.nordtal.s2.commands.phase;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.phase.SeasonDates;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * {@code /phase show} - and the bare {@code /phase} on the proxy. Reads, writes nothing.
 *
 * <h2>Why the phase can come out before the database is asked</h2>
 * This is the command somebody runs while the network is misbehaving, so a process holding the
 * phase in memory says it <em>first</em> and asks the database afterwards. The proxy does hold it
 * ({@code PhaseWatch}); the bot does not, and reads. Both paths end with the same two lines in the
 * same order, which is exactly what {@link PhaseEffects#observation()} exists to make possible
 * without either process knowing about the other.
 *
 * <p>Whether the first line is an observation or the never-read fallback is stated rather than
 * hidden: "the network is in MAINTENANCE" and "the network has not been readable, so it is being
 * treated as MAINTENANCE" are different facts, and only one of them is a reason to panic.</p>
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
            final Instant launch;
            final Instant smpStart;
            try {
                if (held.isEmpty()) {
                    sayPhase(user, effects.phases().currentPhase(), true);
                }
                launch = effects.phases().launch().orElse(null);
                smpStart = effects.phases().smpStart().orElse(null);
            } catch (final RuntimeException failure) {
                effects.warn("reading the season dates for /phase show", failure);
                // Always an answer, and which one depends on whether a phase line went out. The
                // guard here used to suppress the sentence entirely on the path with no cache -
                // the bot's, and a request claimed off a row - which left a Discord interaction
                // with no response at all and settled a request row empty. It was suppressed
                // because phase.read.failed says "the phase above", and on that path there is
                // nothing above; so the answer is a second key rather than no key.
                user.reply(held.isPresent() ? "phase.read.failed" : "phase.read.failed.only");
                return;
            }

            user.reply("phase.dates", Map.of(
                    "launch", SeasonDates.format(launch),
                    "smpStart", SeasonDates.format(smpStart),
                    "zone", SeasonDates.ZONE.getId()));
        });
    }

    private static void sayPhase(final NordtalUser user, final SeasonPhase phase,
                                 final boolean everRead) {
        user.reply(everRead ? "phase.current" : "phase.current.unread",
                Map.of("phase", phase.name()));
    }
}
