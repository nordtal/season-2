package eu.nordtal.season.hungergames.config;

import eu.nordtal.season.settings.Refers;
import eu.nordtal.season.spec.annotation.ConfigSpec;
import eu.nordtal.season.spec.annotation.Explain;
import eu.nordtal.season.spec.annotation.Key;
import eu.nordtal.season.spec.annotation.Name;
import eu.nordtal.season.spec.annotation.NoExplanationNeeded;
import eu.nordtal.season.spec.annotation.Order;
import eu.nordtal.season.spec.annotation.Reload;
import java.util.List;

/** The {@code config} group of the Hunger Games. */
@ConfigSpec
public interface HungerGamesSpec {

    /** The smallest number of participants. {@code /hg start} refuses a start below it. */
    int HARD_MINIMUM_PARTICIPANTS = 2;

    /** The seconds that players are frozen on their spawn towers. */
    @Order(1)
    @Name("Countdown (seconds)")
    @Key("countdown-seconds")
    @NoExplanationNeeded
    default int countdownSeconds() {
        return 60;
    }

    /** The number of participants below which {@code /hg start} needs a confirmation. */
    @Order(2)
    @Name("Soft minimum of players")
    @Key("soft-minimum-participants")
    @NoExplanationNeeded
    default int softMinimumParticipants() {
        return 4;
    }

    @Order(3)
    @Name("Border start diameter")
    @Key("border-start-diameter")
    @NoExplanationNeeded
    default double borderStartDiameter() {
        return 250.0;
    }

    @Order(4)
    @Name("Border end diameter")
    @Key("border-end-diameter")
    @NoExplanationNeeded
    default double borderEndDiameter() {
        return 1.0;
    }

    @Order(5)
    @Name("Border wall speed (blocks per second)")
    @Key("border-wall-speed-blocks-per-second")
    @Explain("Keep it under walking speed (4.317), or players cannot outrun the wall.")
    default double borderWallSpeedBlocksPerSecond() {
        return 6.0;
    }

    /** The seconds without a death after which the passive shrink starts. */
    @Order(6)
    @Name("Border quiet period (seconds)")
    @Key("border-quiet-period-seconds")
    @NoExplanationNeeded
    default int borderQuietPeriodSeconds() {
        return 600;
    }

    /** The diameter that the passive shrink removes per hour. */
    @Order(7)
    @Name("Border passive shrink (blocks per hour)")
    @Key("border-passive-shrink-blocks-per-hour")
    @NoExplanationNeeded
    default double borderPassiveShrinkBlocksPerHour() {
        return 15.0;
    }

    @Order(8)
    @Name("PvP protection (seconds)")
    @Key("pvp-protection-seconds")
    @NoExplanationNeeded
    default int pvpProtectionSeconds() {
        return 60;
    }

    @Order(9)
    @Name("Spawn tower radius")
    @Key("spawn-tower-radius")
    @NoExplanationNeeded
    default double spawnTowerRadius() {
        return 100.0;
    }

    @Order(10)
    @Name("Spawn tower height")
    @Key("spawn-tower-height")
    @NoExplanationNeeded
    default double spawnTowerHeight() {
        return 4.0;
    }

    /** The folder name of the world of the Hunger Games. */
    @Order(11)
    @Name("World name")
    @Key("world-name")
    @NoExplanationNeeded
    default String worldName() {
        return "hunger_games";
    }

    /** The lobby settings. */
    @Order(12)
    @Name("Lobby")
    @Key("lobby")
    @NoExplanationNeeded
    LobbySpec lobby();

    /** The loot points. */
    @Order(13)
    @Name("Loot points")
    @Key("loot-points")
    @NoExplanationNeeded
    default List<LootPointSpec> lootPoints() {
        return DefaultLootPoints.LIST;
    }

    /** The refill tiers, ordered by delay. */
    @Order(14)
    @Name("Refill tiers")
    @Key("refill-tiers")
    @NoExplanationNeeded
    default List<RefillTierSpec> refillTiers() {
        return DefaultRefillTiers.LIST;
    }

    @Reload
    void reload();

    /** The lobby settings. */
    @ConfigSpec
    interface LobbySpec {

        @Order(1)
        @Name("X")
        @Key("x")
        @NoExplanationNeeded
        default double x() {
            return 0.0;
        }

        @Order(2)
        @Name("Y")
        @Key("y")
        @NoExplanationNeeded
        default double y() {
            return 200.0;
        }

        @Order(3)
        @Name("Z")
        @Key("z")
        @NoExplanationNeeded
        default double z() {
            return 0.0;
        }

        /** The seconds between two ready broadcasts. */
        @Order(4)
        @Name("Broadcast interval (seconds)")
        @Key("broadcast-interval-seconds")
        @NoExplanationNeeded
        default int broadcastIntervalSeconds() {
            return 300;
        }

        /** The columns of the map grid. The grid shows {@code map-<lang>.png}. */
        @Order(5)
        @Name("Map grid columns")
        @Key("map-grid-columns")
        @NoExplanationNeeded
        default int mapGridColumns() {
            return 3;
        }

        @Order(6)
        @Name("Map grid rows")
        @Key("map-grid-rows")
        @NoExplanationNeeded
        default int mapGridRows() {
            return 3;
        }

        /** The X coordinate of the map frame origin. */
        @Order(7)
        @Name("Map frame origin X")
        @Key("map-frame-origin-x")
        @NoExplanationNeeded
        default int mapFrameOriginX() {
            return 0;
        }

        /** The Y coordinate of the map frame origin. */
        @Order(8)
        @Name("Map frame origin Y")
        @Key("map-frame-origin-y")
        @NoExplanationNeeded
        default int mapFrameOriginY() {
            return 196;
        }

        /** The Z coordinate of the map frame origin. */
        @Order(9)
        @Name("Map frame origin Z")
        @Key("map-frame-origin-z")
        @NoExplanationNeeded
        default int mapFrameOriginZ() {
            return 0;
        }
    }

    /** A loot point with a label and a position. */
    @ConfigSpec
    interface LootPointSpec {

        /** The name in the refill announcements. */
        @Order(1)
        @Name("Label")
        @Key("label")
        @NoExplanationNeeded
        default String label() {
            return "spawn";
        }

        @Order(2)
        @Name("X")
        @Key("x")
        @NoExplanationNeeded
        default double x() {
            return 0.0;
        }

        @Order(3)
        @Name("Y")
        @Key("y")
        @NoExplanationNeeded
        default double y() {
            return 64.0;
        }

        @Order(4)
        @Name("Z")
        @Key("z")
        @NoExplanationNeeded
        default double z() {
            return 0.0;
        }
    }

    /** A refill with a delay and the items of every loot chest. */
    @ConfigSpec
    interface RefillTierSpec {

        /** The minutes after the release at which the refill happens. */
        @Order(1)
        @Name("Delay (minutes)")
        @Key("delay-minutes")
        @Explain("Changing it on an existing entry retires that tier.")
        default int delayMinutes() {
            return 0;
        }

        /** The items that the refill puts in each loot chest after it clears the chest. */
        @Order(2)
        @Name("Items")
        @Key("items")
        @NoExplanationNeeded
        @Refers(Refers.To.ITEM)
        default List<String> items() {
            return List.of("BREAD");
        }
    }
}
