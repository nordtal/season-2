package eu.nordtal.s2.smp.db;

/**
 * Where an amount of aura sits on the board, and how big the board is.
 *
 * @param place one plus everybody with strictly more aura, so ties share a place
 * @param total how many people have an aura row and a linked account
 */
public record AuraPlace(int place, int total) {}
