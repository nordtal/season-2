package eu.nordtal.s2.commands.hungergames;

import static eu.nordtal.s2.commands.CommandMessages.MESSAGES;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;
import eu.nordtal.s2.common.message.Tone;
import eu.nordtal.s2.common.message.context.TeamContext;
import java.util.List;
import java.util.Optional;

/**
 * {@code /hg ready-status}: which teams are ready, by name.
 *
 * A team is ready when every member is, the same merge as the lobby's "{ready}/{total} teams ready" line.
 */
public final class ReadyStatus implements NordtalCommand<HungerGamesEffects> {

    @Override
    public Declaration declaration() {
        return HungerGamesCommands.READY_STATUS;
    }

    @Override
    public void run(final NordtalUser user, final Values values, final HungerGamesEffects effects) {
        effects.async(() -> {
            final Optional<HungerGamesEffects.Registration> registration = effects.registration();
            if (registration.isEmpty()) {
                user.reply(MESSAGES.hg().start().noGame(), Feedback.REFUSED, Tone.WARN);
                return;
            }

            final List<HungerGamesEffects.TeamReady> teams =
                    effects.readyStatus(registration.get().gameId());
            user.reply(MESSAGES.hg().readyStatus().header(), Tone.NEUTRAL);
            // The admin is looking for who is NOT ready yet, so the tone carries the answer.
            teams.forEach(team -> user.reply(
                    MESSAGES.hg()
                            .readyStatus()
                            .line(
                                    new TeamContext(team.team()),
                                    // A nested message, resolved in the reader's own language.
                                    user.phrase(
                                            team.ready()
                                                    ? MESSAGES.hg()
                                                            .readyStatus()
                                                            .ready()
                                                    : MESSAGES.hg()
                                                            .readyStatus()
                                                            .notReady())),
                    team.ready() ? Tone.GOOD : Tone.MUTED));
        });
    }
}
