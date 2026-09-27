package eu.nordtal.s2.smp.command;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The two reads a player's own commands need, an interface so {@link PlayerCommands} can be tested without a server.
 */
public interface Standing {

    /**
     * What {@code /smp status} says.
     *
     * @param phase the season phase's name, as {@code SeasonPhase#name()}
     * @param read false before the plugin's first season refresh
     * @param milestone the active milestone's name in the asker's language, or empty when every milestone is done
     * @param percent how far the active milestone is, 0 to 100
     * @param online how many players are on the SMP right now
     */
    record Status(String phase, boolean read, Optional<String> milestone, int percent, int online) {}

    /** One line of the aura leaderboard, already resolved to a name; {@code you} marks the asker's own. */
    record AuraLine(int place, String player, int aura, boolean you) {}

    /**
     * Where the asker stands, and who is at the top.
     *
     * @param aura the asker's own aura
     * @param rank one more than the number of people with strictly more aura, so ties share a place
     * @param total how many people have an aura row at all
     * @param top the highest ten, most first
     */
    record AuraStanding(int aura, int rank, int total, List<AuraLine> top) {}

    /**
     * Returns what {@code /smp status} says, off the main thread.
     *
     * @param locale the asker's language, for the milestone's name
     */
    Status status(Locale locale);

    /**
     * Returns where somebody stands and the top of the board, in one read, off the main thread.
     *
     * Empty without a Discord link, which the login gate prevents but this layer must not assume.
     */
    Optional<AuraStanding> auraStanding(UUID player);
}
