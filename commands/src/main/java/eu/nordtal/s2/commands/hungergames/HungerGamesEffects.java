package eu.nordtal.s2.commands.hungergames;

import eu.nordtal.s2.commands.CommandEffects;
import eu.nordtal.s2.commands.NordtalUser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything {@code /hg} touches that only the hunger games server can reach.
 *
 * {@link #registration()} answers game, state and the resolved participant count in one read.
 */
public interface HungerGamesEffects extends CommandEffects {

    /**
     * The game that exists right now.
     *
     * @param gameId       which game
     * @param state        its state, as {@code hg_game.state} spells it
     * @param participants how many will actually be teleported, demotions resolved
     */
    record Registration(UUID gameId, String state, int participants) {}

    /** One team's readiness, for {@code /hg ready-status}. */
    record TeamReady(String team, boolean ready) {}

    /** Returns the registered game, or empty when none is. */
    Optional<Registration> registration();

    /** Starts it; everything after this point is the event. */
    void start(UUID gameId);

    /** Re-reads {@code sounds.yml}. */
    boolean reloadSounds();

    /** Re-reads the message bundles and the operator's override, separately from the sounds. */
    boolean reloadMessages();

    /** Returns which teams have said they are ready, in a stable order. */
    List<TeamReady> readyStatus(UUID gameId);

    /** Returns the recommended minimum from this server's {@code config.yml}. */
    int softMinimumParticipants();

    /** Files the start where this process files admin actions. */
    void recordStart(NordtalUser who, Registration game, boolean confirmedBelowMinimum);
}
