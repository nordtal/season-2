package eu.nordtal.s2.smp.aura;

/**
 * What went into {@code smp_aura_event.reason}.
 *
 * <p>The column is deliberately <b>not</b> CHECK-constrained in {@code V6__smp.sql} - "a new aura
 * source must not need a migration" - so this enum is the plugin's own closed set over an open
 * column, not a mirror of a database constraint. A future source is a constant here and nothing
 * else; a source that only ever existed in an older version still reads back out of the ledger as
 * its own string.
 *
 * <p>The ledger exists so a leaderboard position can always be explained, and the case it was
 * written for is the one the design deliberately does not protect against: repeatedly killing
 * somebody drains a publicly visible number with no daily cap and no per-killer cooldown, so
 * "why did I lose 40 aura overnight" has to be answerable from the data.
 */
public enum AuraReason {

    /** Won a duel. The loser paid exactly this, so a duel only ever moves aura between two people. */
    DUEL_WIN,

    /** Lost a duel, or disconnected during one - which counts as a defeat and books the aura. */
    DUEL_LOSS,

    /** An ordinary death, anywhere except the duel arena. */
    DEATH,

    /** A death by one of the configured "embarrassing" causes, which costs more. */
    DEATH_LISTED,

    /** A share of an objective's pot, paid when the objective completes. */
    CONTRIBUTION,

    /** One of the curated advancements, once per player. */
    ADVANCEMENT,

    /** The hunger games winner's head start, paid on that player's first join and never again. */
    HG_WINNER,

    /** An admin booked it by hand. */
    ADMIN;

    /** @return the string written to {@code smp_aura_event.reason}, which is {@code varchar(32)} */
    public String stored() {
        return name();
    }
}
