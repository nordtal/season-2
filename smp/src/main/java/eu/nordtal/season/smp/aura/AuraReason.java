package eu.nordtal.season.smp.aura;

/**
 * What went into {@code smp_aura_event.reason}, which exists so a leaderboard position can always be explained.
 *
 * The column has no CHECK constraint, so a new source is a constant here and an old one still reads back.
 */
public enum AuraReason {

    /** Won a duel; the loser paid exactly this, so a duel only moves aura between two people. */
    DUEL_WIN,

    /** Lost a duel, or disconnected during one. */
    DUEL_LOSS,

    /** An ordinary death, anywhere except the duel arena. */
    DEATH,

    /** A death by one of the configured "embarrassing" causes, which costs more. */
    DEATH_LISTED,

    /** A share of an objective's pot, paid when the objective completes. */
    CONTRIBUTION,

    /** One of the curated advancements, once per player. */
    ADVANCEMENT,

    /** An admin booked it by hand. */
    ADMIN;

    /** Returns the string written to {@code smp_aura_event.reason}, which is {@code varchar(32)}. */
    public String stored() {
        return name();
    }
}
