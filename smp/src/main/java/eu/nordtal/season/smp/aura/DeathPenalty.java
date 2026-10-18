package eu.nordtal.season.smp.aura;

import eu.nordtal.season.papercommon.game.GameKeys;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The aura a death costs: 5 for an ordinary death, 20 for a listed cause and nothing in the duel arena.
 *
 * Causes are lowercase strings, so an unknown damage type logs a warning at startup and does not fail the load.
 */
public final class DeathPenalty {

    public static final int DEFAULT_ORDINARY = 5;

    public static final int DEFAULT_LISTED = 20;

    private final int ordinary;
    private final int listed;
    private final Set<String> listedCauses;

    /**
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
     * @param damageType the damage type in any case, with or without a namespace, or null when unknown
     * @param inArena whether the death happened inside a duel arena
     */
    public int deltaFor(final @Nullable String damageType, final boolean inArena) {
        if (inArena) {
            return 0;
        }
        return isListed(damageType) ? -listed : -ordinary;
    }

    public AuraReason reasonFor(final @Nullable String damageType) {
        return isListed(damageType) ? AuraReason.DEATH_LISTED : AuraReason.DEATH;
    }

    public boolean isListed(final @Nullable String damageType) {
        return damageType != null && listedCauses.contains(GameKeys.key(damageType));
    }

    public Set<String> listedCauses() {
        return listedCauses;
    }
}
