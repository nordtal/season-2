package eu.nordtal.s2.smp.db;

/**
 * One player's whole composition, in one row.
 *
 * <p>Four tables - {@code account_link}, {@code discord_user}, {@code smp_player} and
 * {@code player_playtime} - joined into a single round trip, because this is read once per join and
 * a join is a moment where four sequential queries would be four chances to be slow.
 *
 * <p>{@code aura} and {@code playtimeSeconds} are boxed: a player who has never earned aura has no
 * {@code smp_player} row, and one who has never been counted by the proxy has no
 * {@code player_playtime} row. Null there means "none yet" and is not an error.
 */
public record IdentityRow(String locale, Boolean admin, Boolean donor, Integer aura,
                          Long playtimeSeconds) {
}
