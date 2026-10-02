package eu.nordtal.s2.hungergames.config;

import eu.nordtal.s2.settings.Checks;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;

/**
 * What a valid {@code config} group of the hunger games is beyond its types.
 *
 * A reload re-reads only the {@code sounds} group, so a border parameter never moves mid-game.
 */
public final class HungerGamesCheck {

    private HungerGamesCheck() {}

    /**
     * Refuses a {@code config} group no game can be run from.
     *
     * @throws IllegalArgumentException naming the first value that is wrong
     */
    public static void check(final HungerGamesSpec config) {
        validateScalars(config);
        validateLootPoints(config);
        validateRefillTiers(config);
    }

    private static void validateScalars(final HungerGamesSpec config) {
        Checks.requirePositive("countdown-seconds", config.countdownSeconds());
        // Zero or negative would not disable the watcher, it would poll once per second.
        if (config.softMinimumParticipants() < HungerGamesSpec.HARD_MINIMUM_PARTICIPANTS) {
            throw new IllegalArgumentException(
                    "soft-minimum-participants must be at least " + HungerGamesSpec.HARD_MINIMUM_PARTICIPANTS
                            + " (the hard, non-configurable floor), was " + config.softMinimumParticipants());
        }

        if (config.borderEndDiameter() <= 0) {
            throw new IllegalArgumentException("border-end-diameter must be greater than zero");
        }
        if (config.borderStartDiameter() <= config.borderEndDiameter()) {
            throw new IllegalArgumentException("border-start-diameter must be greater than border-end-diameter");
        }
        Checks.requirePositive("border-wall-speed-blocks-per-second", config.borderWallSpeedBlocksPerSecond());
        Checks.requirePositive("border-quiet-period-seconds", config.borderQuietPeriodSeconds());
        Checks.requirePositive("border-passive-shrink-blocks-per-hour", config.borderPassiveShrinkBlocksPerHour());
        Checks.requirePositive("pvp-protection-seconds", config.pvpProtectionSeconds());
        Checks.requirePositive("spawn-tower-radius", config.spawnTowerRadius());
        Checks.requireText("world-name", config.worldName());
    }

    private static void validateLootPoints(final HungerGamesSpec config) {
        final List<HungerGamesSpec.LootPointSpec> points = config.lootPoints();
        if (points == null || points.size() != 5) {
            throw new IllegalArgumentException("loot-points must have exactly 5 entries (the spawn plus four staggered "
                    + "points), had "
                    + (points == null ? 0 : points.size()));
        }
        final Set<String> labels = new HashSet<>();
        for (final HungerGamesSpec.LootPointSpec point : points) {
            if (point.label() == null || point.label().isBlank()) {
                throw new IllegalArgumentException("loot-points: every entry needs a non-blank label");
            }
            if (!labels.add(point.label())) {
                throw new IllegalArgumentException("loot-points: duplicate label '" + point.label() + "'");
            }
        }
    }

    private static void validateRefillTiers(final HungerGamesSpec config) {
        final List<HungerGamesSpec.RefillTierSpec> tiers = config.refillTiers();
        if (tiers == null || tiers.isEmpty()) {
            throw new IllegalArgumentException("refill-tiers must not be empty");
        }
        int previousDelay = -1;
        final Set<Integer> delays = new HashSet<>();
        for (final HungerGamesSpec.RefillTierSpec tier : tiers) {
            if (tier.delayMinutes() < 0) {
                throw new IllegalArgumentException("refill-tiers: delay-minutes must not be negative");
            }
            if (!delays.add(tier.delayMinutes())) {
                throw new IllegalArgumentException("refill-tiers: duplicate delay-minutes " + tier.delayMinutes());
            }
            if (tier.delayMinutes() < previousDelay) {
                throw new IllegalArgumentException("refill-tiers must be ordered by ascending delay-minutes");
            }
            previousDelay = tier.delayMinutes();

            if (tier.items() == null || tier.items().isEmpty()) {
                throw new IllegalArgumentException(
                        "refill-tiers: tier at " + tier.delayMinutes() + " minutes has no items");
            }
            for (final String item : tier.items()) {
                if (Material.matchMaterial(item) == null) {
                    throw new IllegalArgumentException("refill-tiers: '" + item + "' is not a known material (tier at "
                            + tier.delayMinutes() + " minutes)");
                }
            }
        }
    }
}
