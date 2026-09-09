package eu.nordtal.s2.hungergames.config;

import eu.nordtal.jcore.config.spec.annotation.Comment;
import eu.nordtal.jcore.config.spec.annotation.ConfigSpec;
import eu.nordtal.jcore.config.spec.annotation.Key;
import eu.nordtal.jcore.config.spec.annotation.Order;
import eu.nordtal.jcore.config.spec.annotation.Reload;

import java.util.List;

/**
 * {@code config/config.yml} - everything the hunger games start event needs that is not a database
 * credential.
 *
 * <p>The hard minimum of two participants is {@link #HARD_MINIMUM_PARTICIPANTS}, a constant rather
 * than a setting, because it is arithmetic: the border step divides by
 * {@code participants - 1}. {@link #softMinimumParticipants()} is the configurable one, below which
 * a start needs a confirmation.</p>
 *
 * <p>Coordinates get real placeholder defaults an operator fills in once the hand-built world
 * exists; they are world data, not secrets, unlike anything Discord-shaped.</p>
 */
@ConfigSpec(header = {
        "-------------------------------------------------------------------",
        "  hunger-games - the season 2 start event",
        "-------------------------------------------------------------------",
        "Every setting here can be overridden with an environment variable",
        "named NORDTAL_HUNGER_GAMES_<PATH>, with '.' and '-' both becoming",
        "'_':",
        "",
        "  countdown-seconds  ->  NORDTAL_HUNGER_GAMES_COUNTDOWN_SECONDS",
        "",
        "The environment wins over this file and is never written back into",
        "it. A setting this file does not declare is deleted on the next",
        "start, with a warning and a copy of the old file in config.yml.bak",
        "- unless it looks like a MISSPELLING of a real one, which stops",
        "the plugin instead, because only you know what you meant by it.",
        "",
        "The loot point and lobby coordinates are placeholders: the actual",
        "event world is hand-built and does not exist in this repository",
        "yet. Fill them in once it does."
})
public interface HungerGamesSpec {

    /**
     * The hard floor below which {@code /hg start} refuses outright: the border step divides by
     * {@code participants - 1}, so a lower value is not strict, it is broken. An alias for the
     * constant in {@code :commands}, which cannot see this interface - one number, not two that
     * have to agree.
     */
    int HARD_MINIMUM_PARTICIPANTS =
            eu.nordtal.s2.commands.hungergames.HungerGamesCommands.HARD_MINIMUM_PARTICIPANTS;

    @Order(1)
    @Key("countdown-seconds")
    @Comment("How long players are frozen on their spawn towers before release.")
    default int countdownSeconds() {
        return 60;
    }

    @Order(2)
    @Key("soft-minimum-participants")
    @Comment({
            "Below this many effective (post-demotion) participants, /hg start asks for",
            "confirmation instead of starting outright, so a rehearsal with a handful of real",
            "clients is not blocked by a rule meant to catch a mis-click. The hard floor of "
                    + HARD_MINIMUM_PARTICIPANTS + " below which",
            "the command refuses outright is arithmetic, not configurable."
    })
    default int softMinimumParticipants() {
        return 4;
    }

    @Order(3)
    @Key("border-start-diameter")
    @Comment("The world border's diameter at the start of the game, in blocks.")
    default double borderStartDiameter() {
        return 250.0;
    }

    @Order(4)
    @Key("border-end-diameter")
    @Comment("The floor the border shrinks to and never passes, in blocks.")
    default double borderEndDiameter() {
        return 1.0;
    }

    @Order(5)
    @Key("border-wall-speed-blocks-per-second")
    @Comment({
            "How fast the border moves once a death-triggered shrink starts, in blocks of",
            "DIAMETER change per second - the wall itself moves at half that rate, so this value",
            "is already doubled.",
            "",
            "The wall must stay just under walking speed (4.317 blocks/s). The default 6.0",
            "diameter-blocks/s is a 3.0 blocks/s wall, about 70% of that, leaving margin for a",
            "player who has to dodge terrain or another player."
    })
    default double borderWallSpeedBlocksPerSecond() {
        return 6.0;
    }

    @Order(6)
    @Key("border-quiet-period-seconds")
    @Comment({
            "How long the game can go with no death before the passive shrink kicks in.",
            "",
            "Ten minutes is long relative to a fight and short relative to the whole event, so",
            "ordinary lulls - looting, travelling, waiting out another fight - do not trigger a",
            "shrink that then fights the next death-triggered one."
    })
    default int borderQuietPeriodSeconds() {
        return 600;
    }

    @Order(7)
    @Key("border-passive-shrink-blocks-per-hour")
    @Comment({
            "How fast the border shrinks during a passive (quiet-period) shrink, in diameter-",
            "blocks per hour - a much coarser unit than the death-triggered wall speed above,",
            "because this is meant to be barely noticeable minute to minute.",
            "",
            "It only has to be fast enough that a stalemate - a field of disconnected bodies, or a",
            "same-team final two - eventually gets forced together. A death cancels it and resumes",
            "the death-triggered shrink."
    })
    default double borderPassiveShrinkBlocksPerHour() {
        return 15.0;
    }

    @Order(8)
    @Key("pvp-protection-seconds")
    @Comment("How long after release everyone is protected from everyone.")
    default int pvpProtectionSeconds() {
        return 60;
    }

    @Order(9)
    @Key("spawn-tower-radius")
    @Comment("Distance from world spawn each spawn tower is placed at, in blocks.")
    default double spawnTowerRadius() {
        return 100.0;
    }

    @Order(10)
    @Key("spawn-tower-height")
    @Comment("How far above the world's spawn Y level the tower platforms sit, in blocks.")
    default double spawnTowerHeight() {
        return 4.0;
    }

    @Order(11)
    @Key("world-name")
    @Comment({
            "The Bukkit world name the event runs in. Not a snowflake - a world folder name, so it",
            "gets a real (placeholder) default like any other id that is not Discord-shaped."
    })
    default String worldName() {
        return "hunger_games";
    }

    @Order(12)
    @Key("lobby")
    @Comment("The lobby box: its teleport point, the rules/map image grid, and the ready broadcast.")
    LobbySpec lobby();

    @Order(13)
    @Key("loot-points")
    @Comment({
            "Five loot points: the spawn plus four staggered locations. World data, not secrets,",
            "so the coordinates default to real (placeholder) numbers.",
            "",
            "The list may not be empty and must contain exactly 5 entries with unique labels; see",
            "Configs' validator. If you have emptied it, this is the shape:",
            "",
            "  loot-points:",
            "  - label: spawn",
            "    x: 0.0",
            "    y: 64.0",
            "    z: 0.0"
    })
    default List<LootPointSpec> lootPoints() {
        return DefaultLootPoints.LIST;
    }

    @Order(14)
    @Key("refill-tiers")
    @Comment({
            "The loot refill schedule: how long after the start each tier's restock happens, and",
            "what items it stocks every loot point with. A list, so a schedule change is a config",
            "edit and not a release. The default schedule is basic at 0h, iron-level PvP gear at",
            "1h, diamond-level at 2h and overpowered at 2h30.",
            "",
            "The list may not be empty and delays must be unique and ascending; see Configs'",
            "validator. A tier is identified by its delay, so changing 'delay-minutes' on an",
            "existing entry retires that tier."
    })
    default List<RefillTierSpec> refillTiers() {
        return DefaultRefillTiers.LIST;
    }


    // ---------------------------------------------------------------- admin propagation

    @Order(15)
    @Key("admin-poll-interval-seconds")
    @Comment({
            "How often this server re-reads who is an admin, in seconds.",
            "",
            "An admin is a server operator for as long as they are an admin, and the flag lives in",
            "discord_user.admin - nowhere else. Without re-reading it, a revoked admin would keep",
            "operator until they chose to disconnect.",
            "",
            "THIS POLL IS THE GUARANTEE, not the LISTEN connection below. A tick on which nothing",
            "changed costs one indexed query and writes nothing to ops.json, which is what makes it",
            "affordable to run for the life of the server."
    })
    default int adminPollIntervalSeconds() {
        return 30;
    }

    @Order(16)
    @Key("admin-listen-enabled")
    @Comment({
            "Whether to also open a dedicated LISTEN nordtal_admin connection.",
            "",
            "It only makes a revocation feel instant instead of taking up to one poll interval; the",
            "poll above is what is actually guaranteed. Turning this off costs latency and nothing",
            "else, which is why it is a switch: it is one connection per backend, outside the pool,",
            "parked in a blocking read for the life of the server."
    })
    default boolean adminListenEnabled() {
        return true;
    }

    @Reload
    void reload();

    /** The lobby box: teleport point, map/rules image grid, and the periodic ready broadcast. */
    @ConfigSpec
    interface LobbySpec {

        @Order(1)
        @Key("x")
        @Comment("Lobby teleport point, world coordinates.")
        default double x() {
            return 0.0;
        }

        @Order(2)
        @Key("y")
        @Comment("Lobby teleport point, world coordinates.")
        default double y() {
            return 200.0;
        }

        @Order(3)
        @Key("z")
        @Comment("Lobby teleport point, world coordinates.")
        default double z() {
            return 0.0;
        }

        @Order(4)
        @Key("broadcast-interval-seconds")
        @Comment("How often the ready-check broadcast with its clickable ready button repeats.")
        default int broadcastIntervalSeconds() {
            return 300;
        }

        @Order(5)
        @Key("map-grid-columns")
        @Comment({
                "How many Minecraft maps wide the sliced lobby image grid is. The image is sliced",
                "from hunger-games/src/main/resources/lobby/map-<lang>.png, one per language, at",
                "3x3 (384x384px). A missing file is logged and skipped, not a startup failure."
        })
        default int mapGridColumns() {
            return 3;
        }

        @Order(6)
        @Key("map-grid-rows")
        @Comment("How many Minecraft maps tall the sliced lobby image grid is.")
        default int mapGridRows() {
            return 3;
        }

        @Order(7)
        @Key("map-frame-origin-x")
        @Comment({
                "World coordinates of the top-left item frame's block position in the map grid.",
                "The grid extends along +X (columns) and +Y downward (rows); frames must already",
                "exist at these positions in the hand-built lobby - this plugin only sets each",
                "frame's map item, it does not place frames."
        })
        default int mapFrameOriginX() {
            return 0;
        }

        @Order(8)
        @Key("map-frame-origin-y")
        @Comment("World coordinates of the top-left item frame's block position in the map grid.")
        default int mapFrameOriginY() {
            return 196;
        }

        @Order(9)
        @Key("map-frame-origin-z")
        @Comment("World coordinates of the top-left item frame's block position in the map grid.")
        default int mapFrameOriginZ() {
            return 0;
        }
    }

    /** One of the five loot points: a label used in the HUD and announcements, and its position. */
    @ConfigSpec
    interface LootPointSpec {

        @Order(1)
        @Key("label")
        @Comment("A short identifying label, shown in refill announcements. Must be unique.")
        default String label() {
            return "spawn";
        }

        @Order(2)
        @Key("x")
        @Comment("World coordinates.")
        default double x() {
            return 0.0;
        }

        @Order(3)
        @Key("y")
        @Comment("World coordinates.")
        default double y() {
            return 64.0;
        }

        @Order(4)
        @Key("z")
        @Comment("World coordinates.")
        default double z() {
            return 0.0;
        }
    }

    /** One refill: when it happens, and what it stocks every loot point's chest with. */
    @ConfigSpec
    interface RefillTierSpec {

        @Order(1)
        @Key("delay-minutes")
        @Comment("Minutes after the game's release (end of countdown) this refill happens.")
        default int delayMinutes() {
            return 0;
        }

        @Order(2)
        @Key("items")
        @Comment({
                "The item pool for this refill, as a list of Bukkit material names. Every loot",
                "chest is cleared and restocked with one of each - see LootRefill. Material names",
                "are validated at load; an unknown one fails the load with the name that is wrong."
        })
        default List<String> items() {
            return List.of("BREAD");
        }
    }
}
