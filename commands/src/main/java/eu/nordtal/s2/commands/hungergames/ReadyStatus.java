package eu.nordtal.s2.commands.hungergames;

import eu.nordtal.s2.commands.Declaration;
import eu.nordtal.s2.commands.NordtalCommand;
import eu.nordtal.s2.commands.NordtalUser;
import eu.nordtal.s2.commands.Values;
import eu.nordtal.s2.common.feedback.Feedback;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code /hg ready-status} - which teams have said they are ready.
 *
 * <p>A team is ready when every one of its members is, which is the merge the lobby already does
 * for the "{ready}/{total} teams ready" line everybody can see. This is the version that names
 * them.</p>
 */
public final class ReadyStatus implements NordtalCommand<HungerGamesEffects> {

    @Override
    public Declaration declaration() {
        return HungerGamesCommands.READY_STATUS;
    }

    @Override
    public void run(final NordtalUser user, final Values values,
                    final HungerGamesEffects effects) {
        effects.async(() -> {
            final Optional<HungerGamesEffects.Registration> registration = effects.registration();
            if (registration.isEmpty()) {
                user.reply("hg.start.no-game", Map.of(), Feedback.REFUSED);
                return;
            }

            final List<HungerGamesEffects.TeamReady> teams =
                    effects.readyStatus(registration.get().gameId());
            user.reply("hg.ready-status.header");
            teams.forEach(team -> user.reply("hg.ready-status.line",
                    Map.of("team", team.team(),
                            // A nested message, resolved in the reader's own language: this is what
                            // NordtalUser#phrase exists for.
                            "status", user.phrase(team.ready()
                                    ? "hg.ready-status.ready" : "hg.ready-status.not-ready"))));
        });
    }
}
