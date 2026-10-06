package eu.nordtal.season.hungergames.game;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.hungergames.config.HungerGamesSpec;
import eu.nordtal.season.messages.Refusal;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Whether a game may start from the open round now, the same answer for the console and for Steward. */
public final class StartCheck {

    private StartCheck() {}

    /**
     * Returns why the game may not start, or empty when it may.
     *
     * @param underWay     the state of the game in its countdown or running, or {@code null} when none is
     * @param open         whether a round of registration is open to start a game from
     * @param participants the participants after demotion, which the border divides by
     * @param recommended  the minimum below which only a confirmed start goes ahead
     * @param confirmed    whether the asker has seen the numbers
     */
    public static Optional<Refusal> refusal(
            final @Nullable HgGameState underWay,
            final boolean open,
            final SeasonPhase phase,
            final int participants,
            final int recommended,
            final boolean confirmed) {
        if (underWay == null && !open) {
            return Optional.of(ServerRefusal.NO_GAME.with());
        }
        if (phase != SeasonPhase.START_EVENT) {
            return Optional.of(ServerRefusal.WRONG_PHASE.with(phase.name()));
        }
        if (underWay != null) {
            return Optional.of(ServerRefusal.WRONG_STATE.with(underWay.name()));
        }
        if (participants < HungerGamesSpec.HARD_MINIMUM_PARTICIPANTS) {
            return Optional.of(
                    ServerRefusal.BELOW_HARD_MINIMUM.with(HungerGamesSpec.HARD_MINIMUM_PARTICIPANTS, participants));
        }
        if (!confirmed && participants < recommended) {
            return Optional.of(ServerRefusal.BELOW_SOFT_MINIMUM.with(participants, recommended));
        }
        return Optional.empty();
    }
}
