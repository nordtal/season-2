package eu.nordtal.s2.hungergames.game;

import eu.nordtal.s2.common.SeasonPhase;
import eu.nordtal.s2.database.inbox.ServerRefusal;
import eu.nordtal.s2.hungergames.config.HungerGamesSpec;
import eu.nordtal.s2.hungergames.db.GameState;
import eu.nordtal.s2.messages.Refusal;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Whether the registered game may start now, the same answer for the console and for Steward. */
public final class StartCheck {

    private StartCheck() {}

    /**
     * Returns why the game may not start, or empty when it may.
     *
     * @param state        the registered game's state, or {@code null} when no game is open
     * @param participants the participants after demotion, which the border divides by
     * @param recommended  the minimum below which only a confirmed start goes ahead
     * @param confirmed    whether the asker has seen the numbers
     */
    public static Optional<Refusal> refusal(
            final @Nullable GameState state,
            final SeasonPhase phase,
            final int participants,
            final int recommended,
            final boolean confirmed) {
        if (state == null) {
            return Optional.of(ServerRefusal.NO_GAME.with());
        }
        if (phase != SeasonPhase.START_EVENT) {
            return Optional.of(ServerRefusal.WRONG_PHASE.with(phase.name()));
        }
        if (state != GameState.REGISTRATION) {
            return Optional.of(ServerRefusal.WRONG_STATE.with(state.name()));
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
