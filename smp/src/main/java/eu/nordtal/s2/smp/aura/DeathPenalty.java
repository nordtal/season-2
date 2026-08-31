package eu.nordtal.s2.smp.aura;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * What a death costs.
 *
 * <p>docs/smp.md#deaths-cost-aura, decided 2026-08-31: <b>−5 ordinarily, −20 for a listed cause,
 * and nothing in the duel arena.</b> Aura is meant to be a number with risk in it rather than a
 * collection meter that only ever rises - otherwise carefulness counts for nothing and the
 * leaderboard measures diligence alone.
 *
 * <h2>One rule, and the exemptions that were rejected</h2>
 * The arena is the only exemption, and it is not really one: the ±10 stake already settles the
 * fight, so a death penalty on top would make every duel a net loss for both players. It is also
 * the one place with no grave, because nothing real was at stake.
 * <p>
 * Everything else costs - the world border, the void, and dying in the End during the dragon fight,
 * where until the dragon falls dying is the only way home. A list of exemptions was considered and
 * dropped: it is a list somebody has to maintain and argue about, and one rule that always applies
 * is easier to explain to a player than four that sometimes do.
 * </p>
 * <p>
 * There is also <b>no protection against a death drain</b>, deliberately. A daily cap and a
 * per-killer cooldown were both rejected: this server is peaceful by agreement, and the same
 * agreement that governs raiding governs this. What exists instead is the ledger.
 * </p>
 *
 * <h2>Damage types are strings here</h2>
 * The listed causes are configured as damage-type keys and compared as lowercase strings rather
 * than as a Bukkit enum. Two reasons, and the second is the real one: the config is validated at
 * load, when no Bukkit registry is initialised, so a class that resolved them would not be testable
 * without a server - and a damage type the running platform does not have should be a startup
 * warning, not a load failure that takes the plugin down over a typo in a cosmetic list.
 */
public final class DeathPenalty {

    /** What an ordinary death costs, as a positive number. */
    public static final int DEFAULT_ORDINARY = 5;

    /** What one of the listed causes costs, as a positive number. */
    public static final int DEFAULT_LISTED = 20;

    private final int ordinary;
    private final int listed;
    private final Set<String> listedCauses;

    /**
     * @param ordinary     the ordinary penalty, as a positive number of aura
     * @param listed       the listed-cause penalty, as a positive number of aura
     * @param listedCauses the damage-type keys that cost {@code listed}; matched case-insensitively
     *                     and with any {@code minecraft:} namespace stripped, because a config
     *                     written either way means the same thing to the person writing it
     */
    public DeathPenalty(final int ordinary, final int listed, final Set<String> listedCauses) {
        if (ordinary < 0 || listed < 0) {
            throw new IllegalArgumentException(
                    "Death penalties are configured as positive numbers and subtracted here, got "
                            + ordinary + "/" + listed);
        }
        this.ordinary = ordinary;
        this.listed = listed;
        this.listedCauses = Objects.requireNonNull(listedCauses, "listedCauses").stream()
                .filter(Objects::nonNull)
                .map(DeathPenalty::normalise)
                .filter(cause -> !cause.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * @param damageType the damage type the player died to, in any case and with or without a
     *                   namespace; {@code null} for a death with no known cause
     * @param inArena    whether the death happened inside a duel arena
     * @return the aura delta to book, which is zero or negative
     */
    public int deltaFor(final String damageType, final boolean inArena) {
        if (inArena) {
            return 0;
        }
        return isListed(damageType) ? -listed : -ordinary;
    }

    /**
     * @param damageType the damage type, in any case and with or without a namespace
     * @return which of the two reasons to write into the ledger
     */
    public AuraReason reasonFor(final String damageType) {
        return isListed(damageType) ? AuraReason.DEATH_LISTED : AuraReason.DEATH;
    }

    /**
     * @param damageType the damage type, in any case and with or without a namespace
     * @return whether it is one of the configured embarrassing ones
     */
    public boolean isListed(final String damageType) {
        return damageType != null && listedCauses.contains(normalise(damageType));
    }

    /** @return the causes as they are matched, for a startup log that can be checked by eye */
    public Set<String> listedCauses() {
        return listedCauses;
    }

    private static String normalise(final String damageType) {
        final String trimmed = damageType.trim().toLowerCase(Locale.ROOT);
        final int colon = trimmed.indexOf(':');
        return colon < 0 ? trimmed : trimmed.substring(colon + 1);
    }
}
