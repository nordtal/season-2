package eu.nordtal.s2.hungergames.config;

import eu.nordtal.jcore.config.ConfigHandle;
import eu.nordtal.jcore.config.ConfigLoader;
import eu.nordtal.jcore.config.exception.ConfigException;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Where {@code hunger-games}'s config file lives, and every rule about what a valid value is.
 * <p>
 * Same shape as {@code network-control}'s and {@code access-bot}'s own {@code Configs} classes:
 * one file, one environment namespace, every check run once at startup. This is the first Paper
 * plugin in this repository to wire up {@code eu.nordtal.jcore.config} - see
 * {@code season-2/CLAUDE.md}, "Configuration".
 * </p>
 */
public final class Configs {

    private Configs() {
    }

    public static @NotNull ConfigHandle<HungerGamesSpec> load(final Path dataFolder, final Logger logger)
            throws ConfigException {
        final Path file = dataFolder.resolve("config.yml");
        final boolean fresh = !java.nio.file.Files.isRegularFile(file);

        final ConfigHandle<HungerGamesSpec> handle = ConfigLoader.builder(file, HungerGamesSpec.class)
                .envPrefix("NORDTAL_HUNGER_GAMES")
                .validator(Configs::validate)
                .load();

        if (fresh) {
            logger.warn("No config existed at {} - defaults were written and are almost certainly "
                    + "not what you want, especially the world name and every coordinate",
                    file.toAbsolutePath());
        }
        return handle;
    }

    public static @NotNull ConfigHandle<DatabaseSpec> database(final Path dataFolder, final Logger logger)
            throws ConfigException {
        final Path file = dataFolder.resolve("database.yml");
        final boolean fresh = !java.nio.file.Files.isRegularFile(file);

        final ConfigHandle<DatabaseSpec> handle = ConfigLoader.builder(file, DatabaseSpec.class)
                .envPrefix("NORDTAL_HUNGER_GAMES_DATABASE")
                .validator(config -> {
                    requireText("jdbc-url", config.jdbcUrl());
                    requireText("username", config.username());
                    if (!config.jdbcUrl().startsWith("jdbc:postgresql:")) {
                        throw new IllegalArgumentException(
                                "jdbc-url must be a PostgreSQL URL (jdbc:postgresql://host:port/database)");
                    }
                    requirePositive("maximum-pool-size", config.maximumPoolSize());
                    requirePositive("query-timeout-seconds", config.queryTimeoutSeconds());
                })
                .load();

        if (fresh) {
            logger.warn("No config existed at {} - defaults were written and are almost certainly "
                    + "not what you want", file.toAbsolutePath());
        }
        return handle;
    }

    private static void validate(final HungerGamesSpec config) {
        requirePositive("countdown-seconds", config.countdownSeconds());
        if (config.softMinimumParticipants() < HungerGamesSpec.HARD_MINIMUM_PARTICIPANTS) {
            throw new IllegalArgumentException(
                    "soft-minimum-participants must be at least " + HungerGamesSpec.HARD_MINIMUM_PARTICIPANTS
                            + " (the hard, non-configurable floor), was " + config.softMinimumParticipants());
        }

        if (config.borderEndDiameter() <= 0) {
            throw new IllegalArgumentException("border-end-diameter must be greater than zero");
        }
        if (config.borderStartDiameter() <= config.borderEndDiameter()) {
            throw new IllegalArgumentException(
                    "border-start-diameter must be greater than border-end-diameter");
        }
        requirePositive("border-wall-speed-blocks-per-second", config.borderWallSpeedBlocksPerSecond());
        requirePositive("border-quiet-period-seconds", config.borderQuietPeriodSeconds());
        requirePositive("border-passive-shrink-blocks-per-hour", config.borderPassiveShrinkBlocksPerHour());
        requirePositive("pvp-protection-seconds", config.pvpProtectionSeconds());
        requirePositive("spawn-tower-radius", config.spawnTowerRadius());
        requireText("world-name", config.worldName());

        final List<HungerGamesSpec.LootPointSpec> points = config.lootPoints();
        if (points == null || points.size() != 5) {
            throw new IllegalArgumentException(
                    "loot-points must have exactly 5 entries (the spawn plus four staggered "
                            + "points, per docs/hunger-games.md#loot), had "
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
                throw new IllegalArgumentException(
                        "refill-tiers: duplicate delay-minutes " + tier.delayMinutes());
            }
            if (tier.delayMinutes() < previousDelay) {
                throw new IllegalArgumentException(
                        "refill-tiers must be ordered by ascending delay-minutes");
            }
            previousDelay = tier.delayMinutes();

            if (tier.items() == null || tier.items().isEmpty()) {
                throw new IllegalArgumentException(
                        "refill-tiers: tier at " + tier.delayMinutes() + " minutes has no items");
            }
            for (final String item : tier.items()) {
                if (Material.matchMaterial(item) == null) {
                    throw new IllegalArgumentException(
                            "refill-tiers: '" + item + "' is not a known material (tier at "
                                    + tier.delayMinutes() + " minutes)");
                }
            }
        }
    }

    private static void requireText(final String key, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " must not be empty");
        }
    }

    private static void requirePositive(final String key, final double value) {
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be greater than zero, was " + value);
        }
    }
}
