package eu.nordtal.s2.smp.db;

/**
 * One player's whole composition, joined from four tables in one round trip.
 *
 * {@code aura} and {@code playtimeSeconds} are null for a player with no row there yet, which is not an error.
 */
public record IdentityRow(String locale, Boolean admin, Boolean donor, Integer aura, Long playtimeSeconds) {}
