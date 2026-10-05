package eu.nordtal.season.smp.aura;

import eu.nordtal.season.papercommon.game.GameKeys;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What a death costs: 5 ordinarily, 20 for a listed cause, and nothing in the duel arena, whose stake settles it.
 *
 * Causes are lowercase strings, so an unknown damage type is a startup warning rather than a load failure.
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
     * Creates the penalty.
     *
     * @param ordinary the ordinary penalty, as a positive number of aura
     * @param listed the listed-cause penalty, as a positive number of aura
     * @param listedCauses the damage types that cost {@code listed}, as keys or bare minecraft names
     */
    public DeathPenalty(final int ordinary, final int listed, final Set<String> listedCauses) {
        if (ordinary < 0 || listed < 0) {
            throw new IllegalArgumentException(
                    "Death penalties are configured as positive numbers and subtracted here, got " + ordinary + "/"
                            + listed);
        }
        this.ordinary = ordinary;
        this.listed = listed;
        this.listedCauses = Objects.requireNonNull(listedCauses, "listedCauses").stream()
                .filter(Objects::nonNull)
                .map(GameKeys::key)
                .filter(cause -> !cause.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * Returns the aura delta to book, zero or negative.
     *
     * @param damageType the damage type, in any case and with or without a namespace; null when unknown
     * @param inArena whether the death happened inside a duel arena
     */
    public int deltaFor(final @Nullable String damageType, final boolean inArena) {
        if (inArena) {
            return 0;
        }
        return isListed(damageType) ? -listed : -ordinary;
    }

    /**
     * Returns which of the two reasons to write into the ledger.
     *
     * @param damageType the damage type, in any case and with or without a namespace
     */
    public AuraReason reasonFor(final @Nullable String damageType) {
        return isListed(damageType) ? AuraReason.DEATH_LISTED : AuraReason.DEATH;
    }

    /**
     * Returns whether the damage type is one of the configured embarrassing ones.
     *
     * @param damageType the damage type, in any case and with or without a namespace
     */
    public boolean isListed(final @Nullable String damageType) {
        return damageType != null && listedCauses.contains(GameKeys.key(damageType));
    }

    /** Returns the causes as they are matched, for the startup log. */
    public Set<String> listedCauses() {
        return listedCauses;
    }
}
