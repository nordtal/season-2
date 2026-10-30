package eu.nordtal.season.hungergames.config;

import eu.nordtal.season.database.setting.SettingRule;
import eu.nordtal.season.papercommon.game.GameKeys;
import eu.nordtal.season.settings.Checks;
import eu.nordtal.season.settings.SettingRefused;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Checks the {@code config} group. */
public final class HungerGamesCheck {

    /** The spawn plus four staggered points. */
    private static final int LOOT_POINTS = 5;

    private HungerGamesCheck() {}

    /** Throws a {@link SettingRefused} that names the first invalid value. */
    public static void check(final HungerGamesSpec config) {
        validateScalars(config);
        validateLootPoints(config);
        validateRefillTiers(config);
    }

    private static void validateScalars(final HungerGamesSpec config) {
        Checks.requirePositive("countdown-seconds", config.countdownSeconds());
        if (config.softMinimumParticipants() < HungerGamesSpec.HARD_MINIMUM_PARTICIPANTS) {
            throw new SettingRefused(
                    "soft-minimum-participants",
                    SettingRule.AT_LEAST,
                    HungerGamesSpec.HARD_MINIMUM_PARTICIPANTS,
                    "soft-minimum-participants must be at least " + HungerGamesSpec.HARD_MINIMUM_PARTICIPANTS
                            + " (the hard, non-configurable floor), was " + config.softMinimumParticipants());
        }

        Checks.requirePositive("border-end-diameter", config.borderEndDiameter());
        if (config.borderStartDiameter() <= config.borderEndDiameter()) {
            throw new SettingRefused(
                    "border-start-diameter",
                    SettingRule.ABOVE,
                    "border-end-diameter",
                    "border-start-diameter must be greater than border-end-diameter");
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
        if (points == null || points.size() != LOOT_POINTS) {
            throw new SettingRefused(
                    "loot-points",
                    SettingRule.ENTRIES,
                    LOOT_POINTS,
                    "loot-points must have exactly 5 entries (the spawn plus four staggered "
                            + "points), had "
                            + (points == null ? 0 : points.size()));
        }
        final Set<String> labels = new HashSet<>();
        for (final HungerGamesSpec.LootPointSpec point : points) {
            Checks.requireText("loot-points.label", point.label());
            if (!labels.add(point.label())) {
                throw new SettingRefused(
                        "loot-points.label",
                        SettingRule.UNIQUE,
                        point.label(),
                        "loot-points: duplicate label '" + point.label() + "'");
            }
        }
    }

    private static void validateRefillTiers(final HungerGamesSpec config) {
        final List<HungerGamesSpec.RefillTierSpec> tiers = config.refillTiers();
        if (tiers == null || tiers.isEmpty()) {
            throw new SettingRefused("refill-tiers", SettingRule.REQUIRED, "refill-tiers must not be empty");
        }
        int previousDelay = -1;
        final Set<Integer> delays = new HashSet<>();
        for (final HungerGamesSpec.RefillTierSpec tier : tiers) {
            Checks.requireNotNegative("refill-tiers.delay-minutes", tier.delayMinutes());
            if (!delays.add(tier.delayMinutes())) {
                throw new SettingRefused(
                        "refill-tiers.delay-minutes",
                        SettingRule.UNIQUE,
                        tier.delayMinutes(),
                        "refill-tiers: duplicate delay-minutes " + tier.delayMinutes());
            }
            if (tier.delayMinutes() < previousDelay) {
                throw new SettingRefused(
                        "refill-tiers.delay-minutes",
                        SettingRule.ASCENDING,
                        "refill-tiers must be ordered by ascending delay-minutes");
            }
            previousDelay = tier.delayMinutes();

            if (tier.items() == null || tier.items().isEmpty()) {
                throw new SettingRefused(
                        "refill-tiers.items",
                        SettingRule.REQUIRED,
                        "refill-tiers: tier at " + tier.delayMinutes() + " minutes has no items");
            }
            for (final String item : tier.items()) {
                if (GameKeys.material(item).isEmpty()) {
                    throw new SettingRefused(
                            "refill-tiers.items",
                            SettingRule.KNOWN,
                            item,
                            "refill-tiers: '" + item + "' is not a known material (tier at " + tier.delayMinutes()
                                    + " minutes)");
                }
            }
        }
    }
}
