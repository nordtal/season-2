package eu.nordtal.s2.smp.aura;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * What a death costs.
 *
 * <p><b>−5 ordinarily, −20 for a listed cause, and nothing in the duel arena.</b> Aura is meant to
 * be a number with risk in it rather than a meter that only ever rises.
 *
 * <p>The duel arena is the only exemption: the ±10 stake already settles the fight, so a death
 * penalty on top would make every duel a net loss for both players. Everything else costs,
 * including the world border, the void, and dying in the End during the dragon fight. There is
 * deliberately <b>no protection against a death drain</b> - no daily cap, no per-killer cooldown;
 * the ledger is what exists instead.
 *
 * <p>Listed causes are compared as lowercase strings rather than as a Bukkit enum, so that the
 * config can be validated with no registry initialised and a damage type the platform does not have
 * is a startup warning rather than a load failure.
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
     *                     and with any {@code minecraft:} namespace stripped
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
