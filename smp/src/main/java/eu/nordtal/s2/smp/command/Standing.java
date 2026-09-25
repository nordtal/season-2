package eu.nordtal.s2.smp.command;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The two reads a player's own commands need - {@code /aura} and {@code /smp status}.
 *
 * <p>An interface only so {@link PlayerCommands} can be asserted without a server;
 * {@link BukkitSmpEffects} is the one real answer.</p>
 */
public interface Standing {

    /**
     * What {@code /smp status} says.
     *
     * @param phase     the season phase's name, as {@code SeasonPhase#name()}
     * @param milestone the active milestone's display name in the asker's language, or empty when
     *                  every milestone is done
     * @param percent   how far the active milestone is, 0-100, meaningless when it is empty
     * @param online    how many players are on the SMP right now
     */
    record Status(String phase, Optional<String> milestone, int percent, int online) {
    }

    /**
     * One line of the aura leaderboard, already resolved to a name. {@code you} is what lets the
     * asker's own line be coloured differently.
     */
    record AuraLine(int place, String player, int aura, boolean you) {
    }

    /**
     * Where the asker stands, and who is at the top.
     *
     * @param aura  the asker's own aura
     * @param rank  their place, counting everybody with strictly more aura and adding one - so two
     *              people on the same number share a place, which is how a leaderboard reads
     * @param total how many people have an aura row at all, for "4 of 37"
     * @param top   the highest ten, most first; empty on a season where nobody has any yet
     */
    record AuraStanding(int aura, int rank, int total, List<AuraLine> top) {
    }

    /** @param locale the asker's language, for the milestone's name. Off the main thread. */
    Status status(Locale locale);

    /**
     * Where somebody stands, and the top of the board, in one read. Off the main thread.
     *
     * <p>Empty when this account has no Discord link - which the login gate makes impossible in
     * practice and which this layer must not assume, because the gate is another process's rule.</p>
     */
    Optional<AuraStanding> auraStanding(UUID player);
}
