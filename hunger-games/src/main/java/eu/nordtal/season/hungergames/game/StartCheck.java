package eu.nordtal.season.hungergames.game;

import eu.nordtal.season.common.SeasonPhase;
import eu.nordtal.season.database.inbox.ServerRefusal;
import eu.nordtal.season.hungergames.config.HungerGamesSpec;
import eu.nordtal.season.messages.Refusal;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Checks whether a game may start from the open round. */
public final class StartCheck {

    private StartCheck() {}

    /**
     * Returns the refusal, or empty when the game may start.
     *
     * @param underWay    the state of the game in {@code COUNTDOWN} or {@code RUNNING}, {@code null} without one
     * @param recommended the number of participants below which only a confirmed start goes ahead
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
