package eu.nordtal.s2.commands.phase;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.common.phase.PhaseChange;

import java.util.Map;
import java.util.Optional;

/**
 * {@code /phase set &lt;phase&gt;} - the one command in the network that can disconnect everybody.
 *
 * <h2>What this class does not do</h2>
 * It does not confirm. {@link Declaration#irreversible()} is {@code true} for this command and the
 * <b>adapter</b> honours it, because the two surfaces confirm in shapes that have nothing in common:
 * Discord offers a button and only invokes this once it is clicked, the game asks for the command to
 * be typed again inside a short window. By the time {@link #run} is called the answer is already
 * yes. Putting the confirmation here would have meant inventing one shape and forcing the other
 * surface into it.
 *
 * <p>It also does not write the {@code audit_log} row, and must not: {@code switchPhase} writes the
 * row, the audit entry and the {@code NOTIFY} in one statement, so there is no way to switch the
 * phase without the audit entry. A second call filing the same switch would file it twice.</p>
 *
 * <h2>Why the phase name is parsed here rather than read as an enum</h2>
 * {@code SeasonPhase.fromDatabase} answers {@code MAINTENANCE} to anything it does not recognise.
 * That is the right answer for a value read out of a row - an unreadable phase must never be more
 * permissive than the real one - and the worst possible answer for a value that arrived from
 * outside, where a name this build does not know would silently lock the whole network out.
 */
public final class SetPhase implements NordtalCommand<PhaseEffects> {

    @Override
    public Declaration declaration() {
        return PhaseCommands.SET;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final PhaseEffects effects) {
        final String requested = values.string("phase");
        final Optional<SeasonPhase> target = parse(requested);
        if (target.isEmpty()) {
            user.reply("phase.unknown",
                    Map.of("value", requested, "phases", PhaseCommands.names()));
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
                user.reply("phase.failed");
                return;
            }

            effects.recordSwitch(user, change);
            // Do not wait for the poll or the notification to come back around: this process
            // already knows, and refreshing here is what makes the reply and the log agree.
            effects.afterWrite();

            user.reply(change.unchanged() ? "phase.unchanged" : "phase.changed",
                    change.unchanged()
                            ? Map.of("phase", change.current().name())
                            : Map.of("previous", String.valueOf(change.previous()),
                                    "current", change.current().name()));
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

    /** The message key describing what a switch to {@code phase} does to everybody online. */
    public static String consequenceKey(final SeasonPhase phase) {
        return "phase.consequence." + phase.name();
    }
}
