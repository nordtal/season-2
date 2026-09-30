package eu.nordtal.s2.hungergames.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Explain;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Name;
import eu.nordtal.jcore.config.spec.annotation.NoExplanationNeeded;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.jcore.config.spec.annotation.Reload;
import java.util.List;

/** {@code config/config.yml}: everything the hunger games start event needs that is not a database credential. */
@ConfigSpec(
        header = {
            "-------------------------------------------------------------------",
            "  hunger-games: the season 2 start event",
            "-------------------------------------------------------------------",
            "The loot point and lobby coordinates are placeholders until the",
            "hand-built event world exists.",
            "",
            "Every setting can be overridden with an environment variable named",
            "NORDTAL_HUNGER_GAMES_<PATH>, with '.' and '-' both becoming '_'."
        })
public interface HungerGamesSpec {

    /** The floor below which {@code /hg start} refuses, since the border step divides by one less than it. */
    int HARD_MINIMUM_PARTICIPANTS = 2;

    @Order(1)
    @Name("Countdown (seconds)")
    @Key("countdown-seconds")
    @Comment("How long players are frozen on their spawn towers before release.")
    @Explain("How long players are frozen on their spawn towers before release.")
    default int countdownSeconds() {
        return 60;
    }

    @Order(2)
    @Name("Soft minimum of players")
    @Key("soft-minimum-participants")
    @Comment({
        "Below this many participants (after demotion), /hg start asks for confirmation.",
        "Below " + HARD_MINIMUM_PARTICIPANTS + " it refuses outright; that floor is not configurable."
    })
    @Explain(
            "Below this, /hg start asks for confirmation instead of refusing outright; the hard floor is arithmetic and not configurable.")
    default int softMinimumParticipants() {
        return 4;
    }

    @Order(3)
    @Name("Border start diameter")
    @Key("border-start-diameter")
    @Comment("The world border's diameter at the start of the game, in blocks.")
    @NoExplanationNeeded
    default double borderStartDiameter() {
        return 250.0;
    }

    @Order(4)
    @Name("Border end diameter")
    @Key("border-end-diameter")
    @Comment("The floor the border shrinks to and never passes, in blocks.")
    @NoExplanationNeeded
    default double borderEndDiameter() {
        return 1.0;
    }

    @Order(5)
    @Name("Border wall speed (blocks per second)")
    @Key("border-wall-speed-blocks-per-second")
    @Comment({
        "How fast a death-triggered shrink moves, in blocks of DIAMETER per second; the wall",
        "moves at half that. Keep the wall under walking speed (4.317 blocks/s): the default",
        "is a 3.0 blocks/s wall, leaving margin to dodge terrain or another player."
    })
    @Explain(
            "Diameter change per second; the wall moves at half this rate. Keep it under walking speed (4.317 blocks/s).")
    default double borderWallSpeedBlocksPerSecond() {
        return 6.0;
    }

    @Order(6)
    @Name("Border quiet period (seconds)")
    @Key("border-quiet-period-seconds")
    @Comment({
        "How long the game can go with no death before the passive shrink kicks in.",
        "Long enough that an ordinary lull between fights does not trigger it."
    })
    @Explain("How long without a death before the passive shrink starts.")
    default int borderQuietPeriodSeconds() {
        return 600;
    }

    @Order(7)
    @Name("Border passive shrink (blocks per hour)")
    @Key("border-passive-shrink-blocks-per-hour")
    @Comment({
        "How fast the passive (quiet period) shrink moves, in blocks of diameter per hour.",
        "Barely noticeable, only enough to force a stalemate together. A death cancels it."
    })
    @Explain(
            "Blocks of diameter per hour, a much coarser unit than the wall speed above. Only meant to force a stalemate together.")
    default double borderPassiveShrinkBlocksPerHour() {
        return 15.0;
    }

    @Order(8)
    @Name("PvP protection (seconds)")
    @Key("pvp-protection-seconds")
    @Comment("How long after release everyone is protected from everyone.")
    @NoExplanationNeeded
    default int pvpProtectionSeconds() {
        return 60;
    }

    @Order(9)
    @Name("Spawn tower radius")
    @Key("spawn-tower-radius")
    @Comment("Distance from world spawn each spawn tower is placed at, in blocks.")
    @NoExplanationNeeded
    default double spawnTowerRadius() {
        return 100.0;
    }

    @Order(10)
    @Name("Spawn tower height")
    @Key("spawn-tower-height")
    @Comment("How far above the world's spawn Y level the tower platforms sit, in blocks.")
    @NoExplanationNeeded
    default double spawnTowerHeight() {
        return 4.0;
    }

    @Order(11)
    @Name("World name")
    @Key("world-name")
    @Comment({"The Bukkit world folder the event runs in. A placeholder until the event world exists."})
    @Explain("A placeholder: set it to the event world's folder name once the world exists.")
    default String worldName() {
        return "hunger_games";
    }

    @Order(12)
    @Name("Lobby")
    @Key("lobby")
    @Comment("The lobby box: its teleport point, the rules/map image grid, and the ready broadcast.")
    @Explain("The lobby box: teleport point, map/rules image grid, and the periodic ready broadcast.")
    LobbySpec lobby();

    @Order(13)
    @Name("Loot points")
    @Key("loot-points")
    @Comment({
        "Exactly five loot points with unique labels: the spawn plus four staggered locations.",
        "The shape of one entry:",
        "",
        "  loot-points:",
        "  - label: spawn",
        "    x: 0.0",
        "    y: 64.0",
        "    z: 0.0"
    })
    @Explain("Exactly five entries, each with a unique label: the spawn plus four staggered locations.")
    default List<LootPointSpec> lootPoints() {
        return DefaultLootPoints.LIST;
    }

    @Order(14)
    @Name("Refill tiers")
    @Key("refill-tiers")
    @Comment({
        "The loot refill schedule: when each tier restocks every loot point, and with what.",
        "Delays must be unique and ascending. A tier is identified by its delay, so changing",
        "'delay-minutes' on an existing entry retires that tier."
    })
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
        @Comment("Lobby teleport point, world coordinates.")
        @NoExplanationNeeded
        default double x() {
            return 0.0;
        }

        @Order(2)
        @Name("Y")
        @Key("y")
        @Comment("Lobby teleport point, world coordinates.")
        @NoExplanationNeeded
        default double y() {
            return 200.0;
        }

        @Order(3)
        @Name("Z")
        @Key("z")
        @Comment("Lobby teleport point, world coordinates.")
        @NoExplanationNeeded
        default double z() {
            return 0.0;
        }

        @Order(4)
        @Name("Broadcast interval (seconds)")
        @Key("broadcast-interval-seconds")
        @Comment("How often the ready-check broadcast with its clickable ready button repeats.")
        @Explain("How often the ready-check broadcast, with its clickable ready button, repeats.")
        default int broadcastIntervalSeconds() {
            return 300;
        }

        @Order(5)
        @Name("Map grid columns")
        @Key("map-grid-columns")
        @Comment({
            "How many Minecraft maps wide the lobby image grid is, sliced from lobby/map-<lang>.png",
            "per language. A missing file is logged and skipped."
        })
        @Explain(
                "How many maps wide the sliced lobby image is, sliced from map-<lang>.png per language; a missing file is skipped, not a startup failure.")
        default int mapGridColumns() {
            return 3;
        }

        @Order(6)
        @Name("Map grid rows")
        @Key("map-grid-rows")
        @Comment("How many Minecraft maps tall the sliced lobby image grid is.")
        @NoExplanationNeeded
        default int mapGridRows() {
            return 3;
        }

        @Order(7)
        @Name("Map frame origin X")
        @Key("map-frame-origin-x")
        @Comment({
            "World coordinates of the top-left item frame in the map grid, which extends along +X",
            "(columns) and downward (rows). The frames must already exist; this plugin only sets their maps."
        })
        @Explain("The top-left item frame's position. The frames must already exist; this plugin only sets their maps.")
        default int mapFrameOriginX() {
            return 0;
        }

        @Order(8)
        @Name("Map frame origin Y")
        @Key("map-frame-origin-y")
        @Comment("World coordinates of the top-left item frame's block position in the map grid.")
        @Explain("The top-left item frame's position. The frames must already exist; this plugin only sets their maps.")
        default int mapFrameOriginY() {
            return 196;
        }

        @Order(9)
        @Name("Map frame origin Z")
        @Key("map-frame-origin-z")
        @Comment("World coordinates of the top-left item frame's block position in the map grid.")
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
        @Comment("A short identifying label, shown in refill announcements. Must be unique.")
        @Explain("Shown in refill announcements. Must be unique across all five loot points.")
        default String label() {
            return "spawn";
        }

        @Order(2)
        @Name("X")
        @Key("x")
        @Comment("World coordinates.")
        @NoExplanationNeeded
        default double x() {
            return 0.0;
        }

        @Order(3)
        @Name("Y")
        @Key("y")
        @Comment("World coordinates.")
        @NoExplanationNeeded
        default double y() {
            return 64.0;
        }

        @Order(4)
        @Name("Z")
        @Key("z")
        @Comment("World coordinates.")
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
        @Comment("Minutes after the game's release (end of countdown) this refill happens.")
        @Explain(
                "Minutes after release this refill happens; changing it on an existing entry retires that tier rather than rescheduling it.")
        default int delayMinutes() {
            return 0;
        }

        @Order(2)
        @Name("Items")
        @Key("items")
        @Comment({
            "Bukkit material names. Every loot chest is cleared and restocked with one of each.",
            "An unknown name fails the load."
        })
        @Explain(
                "Bukkit material names; every loot chest is cleared and restocked with one of each. An unknown name fails the load.")
        default List<String> items() {
            return List.of("BREAD");
        }
    }
}
