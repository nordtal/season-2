package eu.nordtal.season.smp.aura;

/**
 * The values of {@code smp_aura_event.reason}.
 *
 * The column has no CHECK constraint, so a new source adds a constant and old rows stay readable.
 */
public enum AuraReason {

    /** The winner of a duel. The loser pays the same amount. */
    DUEL_WIN,

    /** The loser of a duel, or a player who disconnected during one. */
    DUEL_LOSS,

    /** A death anywhere except the duel arena. */
    DEATH,

    /** A death by a configured listed cause, which costs more. */
    DEATH_LISTED,

    /** A share of an objective's aura budget, paid when the objective completes. */
    CONTRIBUTION,

    /** A curated advancement, booked once per player. */
    ADVANCEMENT,

    ADMIN;

    public String stored() {
        return name();
    }
}
