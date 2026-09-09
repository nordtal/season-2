package eu.nordtal.s2.smp.db;

/**
 * Where an amount of aura sits on the board, and how big the board is.
 *
 * @param place the position, counting everybody with strictly more aura and adding one - so two
 *              people on the same number share a place, which is how a leaderboard reads
 * @param total how many people have an aura row and a linked account, which is the same population
 *              the board in the world draws from
 */
public record AuraPlace(int place, int total) {
}
