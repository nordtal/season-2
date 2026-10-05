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

/** The {@code config} group: everything the hunger games start event needs that is not a database credential. */
@ConfigSpec
public interface HungerGamesSpec {

    /** The floor below which {@code /hg start} refuses, since the border step divides by one less than it. */
    int HARD_MINIMUM_PARTICIPANTS = 2;

    @Order(1)
    @Name("Countdown (seconds)")
    @Key("countdown-seconds")
    @Explain("How long players are frozen on their spawn towers before release.")
    default int countdownSeconds() {
        return 60;
    }

    @Order(2)
    @Name("Soft minimum of players")
    @Key("soft-minimum-participants")
    @Explain(
            "Below this, /hg start asks for confirmation instead of refusing outright; the hard floor is arithmetic and not configurable.")
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
    @Explain(
            "Diameter change per second; the wall moves at half this rate. Keep it under walking speed (4.317 blocks/s).")
    default double borderWallSpeedBlocksPerSecond() {
        return 6.0;
    }

    @Order(6)
    @Name("Border quiet period (seconds)")
    @Key("border-quiet-period-seconds")
    @Explain("How long without a death before the passive shrink starts.")
    default int borderQuietPeriodSeconds() {
        return 600;
    }

    @Order(7)
    @Name("Border passive shrink (blocks per hour)")
    @Key("border-passive-shrink-blocks-per-hour")
    @Explain(
            "Blocks of diameter per hour, a much coarser unit than the wall speed above. Only meant to force a stalemate together.")
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

    @Order(11)
    @Name("World name")
    @Key("world-name")
    @Explain("A placeholder: set it to the event world's folder name once the world exists.")
    default String worldName() {
        return "hunger_games";
    }

    @Order(12)
    @Name("Lobby")
    @Key("lobby")
    @Explain("The lobby box: teleport point, map/rules image grid, and the periodic ready broadcast.")
    LobbySpec lobby();

    @Order(13)
    @Name("Loot points")
    @Key("loot-points")
    @Explain("Exactly five entries, each with a unique label: the spawn plus four staggered locations.")
    default List<LootPointSpec> lootPoints() {
        return DefaultLootPoints.LIST;
    }

    @Order(14)
    @Name("Refill tiers")
    @Key("refill-tiers")
    @Explain("A schedule ordered by ascending delay; changing 'delay-minutes' on an existing entry retires that tier.")
    default List<RefillTierSpec> refillTiers() {
        return DefaultRefillTiers.LIST;
    }

    @Reload
    void reload();

    /** The lobby box: teleport point, map/rules image grid, and the periodic ready broadcast. */
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

        @Order(4)
        @Name("Broadcast interval (seconds)")
        @Key("broadcast-interval-seconds")
        @Explain("How often the ready-check broadcast, with its clickable ready button, repeats.")
        default int broadcastIntervalSeconds() {
            return 300;
        }

        @Order(5)
        @Name("Map grid columns")
        @Key("map-grid-columns")
        @Explain(
                "How many maps wide the sliced lobby image is, sliced from map-<lang>.png per language; a missing file is skipped, not a startup failure.")
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

        @Order(7)
        @Name("Map frame origin X")
        @Key("map-frame-origin-x")
        @Explain("The top-left item frame's position. The frames must already exist; this plugin only sets their maps.")
        default int mapFrameOriginX() {
            return 0;
        }

        @Order(8)
        @Name("Map frame origin Y")
        @Key("map-frame-origin-y")
        @Explain("The top-left item frame's position. The frames must already exist; this plugin only sets their maps.")
        default int mapFrameOriginY() {
            return 196;
        }

        @Order(9)
        @Name("Map frame origin Z")
        @Key("map-frame-origin-z")
        @Explain("The top-left item frame's position. The frames must already exist; this plugin only sets their maps.")
        default int mapFrameOriginZ() {
            return 0;
        }
    }

    /** One of the five loot points: a label used in the HUD and announcements, and its position. */
    @ConfigSpec
    interface LootPointSpec {

        @Order(1)
        @Name("Label")
        @Key("label")
        @Explain("Shown in refill announcements. Must be unique across all five loot points.")
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

    /** One refill: when it happens, and what it stocks every loot point's chest with. */
    @ConfigSpec
    interface RefillTierSpec {

        @Order(1)
        @Name("Delay (minutes)")
        @Key("delay-minutes")
        @Explain(
                "Minutes after release this refill happens; changing it on an existing entry retires that tier rather than rescheduling it.")
        default int delayMinutes() {
            return 0;
        }

        @Order(2)
        @Name("Items")
        @Key("items")
        @Explain("Every loot chest is cleared and restocked with one of each. An unknown item fails the load.")
        @Refers(Refers.To.ITEM)
        default List<String> items() {
            return List.of("BREAD");
        }
    }
}
