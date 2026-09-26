package eu.nordtal.s2.commands.hungergames;

import eu.nordtal.s2.commands.CommandEffects;
import eu.nordtal.s2.commands.NordtalUser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything {@code /hg} touches that only the hunger games server can reach.
 *
 * The registration is read as one thing: {@link #registration()} answers the game, its state and
 * the participant count together, because
 * the three questions {@code /hg start} asks are one read on the far side and three round trips if
 * they are three methods. The count is already the <em>resolved</em> one - {@code Demotion} applied,
 * so a duo whose partner never showed counts as the full-hearted solo it will become - which is a
 * decision about the game and belongs on this side of the line.
 */
public interface HungerGamesEffects extends CommandEffects {

    /**
     * The game that exists right now, if there is one.
     *
     * @param gameId       which game
     * @param state        its state, as {@code hg_game.state} spells it
     * @param participants how many will actually be teleported, demotions resolved
     */
    record Registration(UUID gameId, String state, int participants) {}

    /** One team's readiness, for {@code /hg ready-status}. */
    record TeamReady(String team, boolean ready) {}

    /** The registered game, or empty when none is. */
    Optional<Registration> registration();

    /** Start it. Everything after this point is the event. */
    void start(UUID gameId);

    /** Re-read {@code sounds.yml}. */
    boolean reloadSounds();

    /**
     * Re-read the message bundles and the operator's override.
     *
     * Separate from the sounds on purpose: a broken {@code sounds.yml} must not stop a corrected
     * message from arriving, and a typo'd override must not read as sounds that failed to load.
     */
    boolean reloadMessages();

    /** Which teams have said they are ready, in a stable order. */
    List<TeamReady> readyStatus(UUID gameId);

    /**
     * The recommended minimum, from this server's {@code config.yml}.
     *
     * Read through the effects rather than carried on the declaration because it is
     * configuration, and configuration lives in the process that owns it - a number baked into a
     * shared declaration would be the same on every deployment and unchangeable without a
     * release.
     */
    int softMinimumParticipants();

    /**
     * File the start where this process files admin actions.
     *
     * The one command that decides the whole event: who, which game, how many participants the
     * arithmetic saw, and whether it went ahead below the recommended minimum.
     */
    void recordStart(NordtalUser who, Registration game, boolean confirmedBelowMinimum);
}
